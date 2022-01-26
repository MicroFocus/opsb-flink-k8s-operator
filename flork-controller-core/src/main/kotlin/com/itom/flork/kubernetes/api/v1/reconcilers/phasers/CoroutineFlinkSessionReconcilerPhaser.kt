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

package com.itom.flork.kubernetes.api.v1.reconcilers.phasers

import com.itom.flork.kubernetes.api.v1.model.FlinkSessionCustomResource
import com.itom.flork.kubernetes.api.v1.model.FlinkSessionSpec
import com.itom.flork.kubernetes.api.v1.model.FlinkSessionStatus
import com.itom.flork.kubernetes.api.v1.model.FlorkPhase
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class CoroutineFlinkSessionReconcilerPhaser internal constructor(
        coroutineScope: CoroutineScope,
        k8sClient: KubernetesClient,
        lister: AtomicReference<Lister<FlinkSessionCustomResource>?>,
        jobKey: String,
        leaseDurationSeconds: Long
) : AbstractReconcilerPhaser<FlinkSessionSpec, FlinkSessionStatus, FlinkSessionCustomResource>(
        coroutineScope,
        k8sClient,
        FlinkSessionCustomResource::class.java,
        lister,
        jobKey,
        leaseDurationSeconds
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(CoroutineFlinkSessionReconcilerPhaser::class.java)
    }

    constructor(
            coroutineScope: CoroutineScope,
            k8sClient: KubernetesClient,
            lister: AtomicReference<Lister<FlinkSessionCustomResource>?>,
            jobKey: String
    ) : this(coroutineScope, k8sClient, lister, jobKey, LEASE_DURATION_SECONDS)

    fun wasGenerationObserved(flinkSession: FlinkSessionCustomResource): Boolean {
        return flinkSession.metadata.generation == observedGeneration && flinkSession.metadata.generation == flinkSession.status.generationDuringLastTransition
    }

    override suspend fun loop() = withContext(Dispatchers.IO) {
        runInterruptible { callbacks.initialReadinessLatch.await() }
        LOG.info("Leader election loop for session '{}' has started.", jobKey)

        for (flinkSession in channel) {
            LOG.info("Processing resource '{}' with generation={}, previous known generation={}, status={}",
                    jobKey, flinkSession.metadata.generation, observedGeneration, flinkSession.status)

            reconcileIfLeading(flinkSession)
        }
    }

    private suspend fun reconcileIfLeading(flinkSession: FlinkSessionCustomResource): Unit = coroutineScope {
        if (leading.get()) {
            val job = launch { reconcile(flinkSession) }
            // previous job should be automatically cancelled by flow (collectLatest),
            // but it doesn't seem to work with launch {}
            callbacks.leaderCoroutine.getAndSet(job)?.cancel()
        } else if (flinkSession.status.florkPhase.terminal) {
            LOG.info("Terminated session received: {}", jobKey)
            launch(NonCancellable) {
                this@CoroutineFlinkSessionReconcilerPhaser.cancel()
            }
        }
    }

    private suspend fun reconcile(flinkSession: FlinkSessionCustomResource): Unit = coroutineScope {
        when (flinkSession.status.florkPhase) {
            FlorkPhase.CREATED -> {
                executeCreationPhase(flinkSession)
            }
            FlorkPhase.DEPLOYING -> {
                LOG.warn("Something failed before state of session '{}' was known, re-deploying.", jobKey)
                executeShutdownPhase(flinkSession)
                executeCreationPhase(flinkSession)
            }
            FlorkPhase.DEPLOYED -> {
                // TODO check if deployment still exists
                if (flinkSession.metadata.generation != flinkSession.status.generationDuringLastTransition) {
                    executeShutdownPhase(flinkSession)
                    executeCreationPhase(flinkSession)
                }
            }
            FlorkPhase.COMPLETED, FlorkPhase.FAILED -> {
                // TODO completed probably won't be used here, should I retry if something failed?
//                if (flinkSession.metadata.generation == flinkSession.status.generationDuringLastTransition) {
//                    LOG.info("Received session '{}' with terminal phase: {}", jobKey, flinkSession.status.florkPhase)
//                    launch(NonCancellable) {
//                        this@CoroutineFlinkSessionReconcilerPhaser.cancel()
//                    }
//                } else {
//                    executeCreationPhase(flinkSession)
//                }
            }
            else -> {
                LOG.warn("Flork-phase in status should never be null, session='{}'.", jobKey)
            }
        }
    }

    private suspend fun executeCreationPhase(flinkSession: FlinkSessionCustomResource) = coroutineScope {
    }

    private suspend fun executeShutdownPhase(flinkSession: FlinkSessionCustomResource) = coroutineScope {
    }

    // generation doesn't change with metadata or status updates
    private suspend fun patchStatus(flinkSession: FlinkSessionCustomResource, updateGeneration: Boolean = true): FlinkSessionCustomResource = coroutineScope {
        if (updateGeneration) {
            flinkSession.status.generationDuringLastTransition = observedGeneration
        }
        crOperations.patchStatus(flinkSession)
    }
}
