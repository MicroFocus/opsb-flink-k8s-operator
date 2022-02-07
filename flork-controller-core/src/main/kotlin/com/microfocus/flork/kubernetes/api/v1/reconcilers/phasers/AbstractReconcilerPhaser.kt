/*
 * Copyright 2021-2022 Micro Focus or one of its affiliates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microfocus.flork.kubernetes.api.v1.reconcilers.phasers

import com.microfocus.flork.kubernetes.api.constants.RuntimeConstants
import com.microfocus.flork.kubernetes.api.v1.reconcilers.utils.DefaultFlinkResourceOperations
import com.microfocus.flork.kubernetes.api.v1.reconcilers.utils.FlinkResourceDeploymentMonitor
import com.microfocus.flork.kubernetes.api.v1.reconcilers.utils.FlinkResourceOperations
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

abstract class AbstractReconcilerPhaser<Spec, Status, T : CustomResource<Spec, Status>>(
        outerScope: CoroutineScope,
        protected val k8sClient: KubernetesClient,
        resourceClass: Class<T>,
        lister: AtomicReference<Lister<T>?>,
        protected val jobKey: String,
        private val leaseDurationSeconds: Long
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(AbstractReconcilerPhaser::class.java)

        const val LEASE_DURATION_SECONDS = 15L

        private fun getBaseLeaderElectionConfig(k8sClient: KubernetesClient, jobKey: String, durationSeconds: Long): LeaderElectionConfigBuilder {
            val sanitizedKey = getLeaseKey(jobKey)
            val deadlineMillis = durationSeconds * 2_000L / 3L
            return LeaderElectionConfigBuilder()
                    .withName(sanitizedKey)
                    .withLock(LeaseLock(k8sClient.namespace, sanitizedKey, RuntimeConstants.POD_NAME))
                    .withLeaseDuration(Duration.ofSeconds(durationSeconds))
                    .withRenewDeadline(Duration.ofMillis(deadlineMillis))
                    .withRetryPeriod(Duration.ofMillis(deadlineMillis / 4L))
        }

        fun getLeaseKey(jobKey: String): String {
            return "flork-lease-${jobKey.replace("/", "-")}"
        }
    }

    val channel = Channel<T>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    protected val leading = AtomicBoolean(false)
    protected val phaserScope = AtomicReference(outerScope)

    protected open val crOperations: FlinkResourceOperations<Spec, Status, T> by lazy {
        DefaultFlinkResourceOperations(k8sClient, resourceClass, lister)
    }

    internal val callbacks by lazy { FlinkResourceLeaderCallbacks() }

    protected val deploymentMonitor: FlinkResourceDeploymentMonitor

    protected var observedGeneration = 0L

    private val leaderElector by lazy {
        val leaderElectionConfig = getBaseLeaderElectionConfig(k8sClient, jobKey, leaseDurationSeconds)
                .withLeaderCallbacks(callbacks)
                .build()

        k8sClient.leaderElector<NamespacedKubernetesClient>()
                .withConfig(leaderElectionConfig)
                .build()
    }

    private val mainCoroutine: Job

    init {
        val jobKeyParts = jobKey.split("/")
        deploymentMonitor = FlinkResourceDeploymentMonitor(jobKeyParts[0], jobKeyParts[1])

        mainCoroutine = outerScope.launch(start = CoroutineStart.LAZY) {
            phaserScope.set(this)
            setUpDeploymentMonitor(k8sClient, jobKeyParts[0], jobKeyParts[1])
            launch {
                runInterruptible {
                    try {
                        leaderElector.run()
                    } finally {
                        LOG.info("Leader election loop for '{}' has finished.", jobKey)
                    }
                }
            }
            loop()
        }
    }

    private suspend fun setUpDeploymentMonitor(k8sClient: KubernetesClient, namespace: String, name: String) = withContext(Dispatchers.IO) {
        LOG.info("Starting deployment watch for resource '{}'.", jobKey)
        runInterruptible {
            k8sClient.resources(Deployment::class.java)
                    .inNamespace(namespace)
                    .withName(name)
                    .inform(deploymentMonitor)
        }
    }

    fun start(finalizerScope: CoroutineScope, resourceKey: String): Boolean {
        val ans = mainCoroutine.start()
        if (ans) {
            val leaseKey = getLeaseKey(resourceKey)

            finalizerScope.launch {
                mainCoroutine.join()

                val lease = runInterruptible {
                    k8sClient.leases()
                            .inNamespace(k8sClient.namespace)
                            .withName(leaseKey)
                            .get()
                }

                val leaseOwnedByMe = lease?.spec?.holderIdentity == RuntimeConstants.POD_NAME
                if (leaseOwnedByMe) {
                    LOG.info("Waiting 1 lease duration period ({}s) before deleting lease.", leaseDurationSeconds)
                    delay(leaseDurationSeconds * 1000L)
                    try {
                        val flag = runInterruptible {
                            k8sClient.leases()
                                    .inNamespace(k8sClient.namespace)
                                    .withName(leaseKey)
                                    .delete()
                        }

                        if (flag) {
                            LOG.info("Lease '{}' supposedly deleted.", leaseKey)
                        } else {
                            LOG.warn("Lease '{}' could not be deleted.", leaseKey)
                        }
                    } catch (e: Exception) {
                        LOG.error("Error during lease deletion:", e)
                    }
                }
            }
        }
        return ans
    }

    fun isActive() = mainCoroutine.isActive

    @Synchronized
    fun cancel() {
        try {
            channel.cancel()
        } finally {
            mainCoroutine.cancel()
        }
    }

    protected abstract suspend fun loop()

    protected open fun onStopLeading() {
        // nop by default
    }
    
    internal inner class FlinkResourceLeaderCallbacks : LeaderCallbacks(
            {
                LOG.info("I'm the leader of '{}' now ({}).", jobKey, RuntimeConstants.POD_NAME)
                leading.set(true)
                callbacks.initialReadinessLatch.countDown()
            },
            {
                LOG.info("Pod '{}' lost leadership of '{}'.", RuntimeConstants.POD_NAME, jobKey)
                leading.set(false)
                try {
                    onStopLeading()
                } finally {
                    callbacks.leaderCoroutine.getAndSet(null)?.cancel()
                }
                callbacks.initialReadinessLatch.countDown()
            },
            { newLeaderId ->
                if (newLeaderId == RuntimeConstants.POD_NAME) {
                    LOG.debug("I'm the leader but onNewLeader was called, waiting for onStartLeading.")
                } else {
                    LOG.info("Pod '{}' is taking over for '{}'.", newLeaderId, jobKey)
                    leading.set(false)
                    callbacks.initialReadinessLatch.countDown()
                }
            }
    ) {
        val initialReadinessLatch = CountDownLatch(1)
        val leaderCoroutine = AtomicReference<Job?>()
    }
}
