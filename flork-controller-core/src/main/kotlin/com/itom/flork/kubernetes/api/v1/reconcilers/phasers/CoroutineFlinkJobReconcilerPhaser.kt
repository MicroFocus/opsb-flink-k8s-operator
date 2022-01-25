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

import com.itom.flork.kubernetes.api.v1.model.*
import com.itom.flork.kubernetes.api.v1.reconcilers.CoroutineFlinkJobReconciler
import com.itom.flork.kubernetes.api.v1.reconcilers.phases.FlinkJobCreatePhase
import com.itom.flork.kubernetes.api.v1.reconcilers.phases.FlinkJobShutdownPhase
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

open class CoroutineFlinkJobReconcilerPhaser internal constructor(
        coroutineScope: CoroutineScope,
        k8sClient: KubernetesClient,
        lister: AtomicReference<Lister<FlinkJobCustomResource>?>,
        jobKey: String,
        leaseDurationSeconds: Long
) : AbstractReconcilerPhaser<FlinkJobSpec, FlinkJobStatus, FlinkJobCustomResource>(
        coroutineScope,
        k8sClient,
        FlinkJobCustomResource::class.java,
        lister,
        jobKey,
        leaseDurationSeconds
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(CoroutineFlinkJobReconcilerPhaser::class.java)
    }

    constructor(
            coroutineScope: CoroutineScope,
            k8sClient: KubernetesClient,
            lister: AtomicReference<Lister<FlinkJobCustomResource>?>,
            jobKey: String
    ) : this(coroutineScope, k8sClient, lister, jobKey, LEASE_DURATION_SECONDS)

    private val setAsDeployedCoroutine = AtomicReference<Job?>()
    private val setAsCompletedCoroutine = AtomicReference<Job?>()

    fun wasGenerationObserved(flinkJob: FlinkJobCustomResource): Boolean {
        return flinkJob.metadata.generation == observedGeneration && flinkJob.metadata.generation == flinkJob.status.generationDuringLastTransition
    }

    override suspend fun loop() = withContext(Dispatchers.IO) {
        runInterruptible { callbacks.initialReadinessLatch.await() }
        LOG.info("Leader election loop for '{}' has started.", jobKey)

        // only 1 channel consumer means a new message won't be consumed until phase transition completes
        // any coroutines that should not block a message's processing should go in phaserScope
        for (flinkJob in channel) {
            LOG.info("Processing resource '{}' with generation={}, previous known generation={}, status={}",
                    jobKey, flinkJob.metadata.generation, observedGeneration, flinkJob.status)

            val currentObservedGeneration = observedGeneration
            try {
                supervisorScope {
                    reconcileIfNecessary(flinkJob)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                observedGeneration = currentObservedGeneration
                LOG.error("Unexpected exception:", e)
            }
        }
    }

    private suspend fun reconcileIfNecessary(flinkJob: FlinkJobCustomResource) = withContext(Dispatchers.IO) {
        val leadingFlag = leading.get()
        val crChanged = observedGeneration > 0L && flinkJob.metadata.generation != observedGeneration
        val reconciliationNeeded = flinkJob.status.generationDuringLastTransition != null && flinkJob.metadata.generation != flinkJob.status.generationDuringLastTransition

        when {
            leadingFlag && flinkJob.status.florkPhase == FlorkPhase.CREATED && callbacks.leaderCoroutine.get() == null -> {
                // nop but highest priority - either it's the very first time we deploy, or we are taking over a crashed leader
                if (observedGeneration > 0L) {
                    LOG.info("Detected new job that wasn't successfully started by previous leader, re-trying: {}", jobKey)
                }
            }
            leadingFlag && (crChanged || reconciliationNeeded) -> {
                when {
                    crChanged || (observedGeneration == 0L && reconciliationNeeded) -> {
                        LOG.info("Definition of Flink job '{}' changed, re-deploying.", jobKey)
                    }
                    reconciliationNeeded -> {
                        LOG.info("Detected job that wasn't reconciled by previous leader, re-deploying: {}", jobKey)
                    }
                }
                if (flinkJob.status.florkPhase.terminal) {
                    flinkJob.status.florkPhase = FlorkPhase.CREATED
                }
            }
            leadingFlag && flinkJob.status.florkPhase == FlorkPhase.DEPLOYING && setAsDeployedCoroutine.get() == null -> {
                // failover scenario - phase is DEPLOYING, but we're not checking if the job manager came up, so something failed
                LOG.warn("Previous leader could not determine if '{}' switched from DEPLOYING to DEPLOYED, re-deploying.", jobKey)
            }
            leadingFlag && flinkJob.status.florkPhase == FlorkPhase.DEPLOYED -> {
                // failover scenario - here we already established CR didn't change and reconciliation didn't fail,
                // but if coroutine isn't active, previous leader failed at some point
                when {
                    setAsCompletedCoroutine.get()?.isActive == true -> {
                        // nop - probably a resync/status-update but everything is normal
                        LOG.debug("Nothing to do after resync/status update for '{}'.", jobKey)
                    }
                    deploymentMonitor.addedFlag.get() -> {
                        LOG.warn("Previous leader could not determine if '{}' switched from DEPLOYED to COMPLETED, setting up new watch.", jobKey)
                        executeCompletionPhase(flinkJob)
                    }
                    else -> {
                        LOG.warn("Kubernetes deployment for '{}' with phase DEPLOYED no longer found.", jobKey)
                        flinkJob.status.florkPhase = FlorkPhase.FAILED
                        patchStatus(flinkJob)
                        // won't delete CR with FAILED phase even if its policy says so
                    }
                }
                observedGeneration = flinkJob.metadata.generation ?: 1L
                return@withContext
            }
            flinkJob.status.florkPhase.terminal -> {
                LOG.debug("Letting phaser cancel itself due to terminated job '{}', leading={}.", jobKey, leadingFlag)
            }
            flinkJob.metadata.generation == observedGeneration -> {
                return@withContext
            }
        }

        reconcileIfLeading(flinkJob, leadingFlag)
    }

    private suspend fun reconcileIfLeading(flinkJob: FlinkJobCustomResource, leadingFlag: Boolean): Unit = withContext(Dispatchers.IO) {
        observedGeneration = flinkJob.metadata.generation ?: 1L

        if (leadingFlag) {
            val job = launch(start = CoroutineStart.LAZY) {
                reconcile(flinkJob)
            }
            callbacks.leaderCoroutine.getAndSet(job)?.cancel()
            job.start()

        } else if (flinkJob.status.florkPhase.terminal) {
            LOG.info("Terminated job received: {}", jobKey)
            launch(NonCancellable) {
                this@CoroutineFlinkJobReconcilerPhaser.cancel()
            }
        }
    }

    private suspend fun reconcile(flinkJob: FlinkJobCustomResource): Unit = withContext(Dispatchers.IO) {
        when (flinkJob.status.florkPhase) {
            FlorkPhase.CREATED -> {
                // if creation returns, flork-phase must be DEPLOYING or FAILED,
                // and a background task checks if DEPLOYED can be set
                executeCreationPhase(flinkJob)
                executeCompletionPhase(flinkJob)
            }
            FlorkPhase.DEPLOYING, FlorkPhase.DEPLOYED -> {
                executeRedeploymentPhase(flinkJob)
            }
            FlorkPhase.COMPLETED, FlorkPhase.FAILED -> {
                LOG.info("Received job '{}' with terminal phase: {}", jobKey, flinkJob.status.florkPhase)
                launch(NonCancellable) {
                    this@CoroutineFlinkJobReconcilerPhaser.cancel()
                }
            }
            else -> {
                LOG.warn("Flork-phase in status should never be null, job='{}'.", jobKey)
            }
        }
    }

    private suspend fun executeCreationPhase(flinkJob: FlinkJobCustomResource) = withContext(Dispatchers.IO) {
        val phase = FlinkJobCreatePhase(jobKey, deploymentMonitor, flinkJob, crOperations, setAsDeployedCoroutine)
        phase.performInitialDeployment(phaserScope.get())
    }

    private suspend fun executeCompletionPhase(flinkJob: FlinkJobCustomResource) = withContext(Dispatchers.IO) {
        val coroutine = phaserScope.get().launch { awaitDeploymentDeletionAndComplete(flinkJob) }
        LOG.info("Waiting for '{}' to terminate.", jobKey)
        setAsCompletedCoroutine.getAndSet(coroutine)?.cancel()
    }

    private suspend fun executeRedeploymentPhase(flinkJob: FlinkJobCustomResource) = withContext(Dispatchers.IO) {
        try {
            // update status with potential savepoint path, but only update that
            flinkJob.status = executeShutdownPhase(flinkJob).status
            // block until we get our own update to be sure that channel doesn't buffer old statuses at all
            while (channel.receive().status.florkPhase != FlorkPhase.COMPLETED) {
                LOG.trace("Status update for redeployment shutdown of '{}' hasn't been received yet.", jobKey)
            }
            runInterruptible {
                CoroutineFlinkJobReconciler.maybeCleanHighAvailability(k8sClient, flinkJob, jobKey)
            }

            executeCreationPhase(flinkJob)
            // similar as above
            while (channel.receive().status.florkPhase != FlorkPhase.DEPLOYING) {
                LOG.trace("Status update for redeployment creation of '{}' hasn't been received yet.", jobKey)
            }
            executeCompletionPhase(flinkJob)
        } catch (t: Throwable) {
            launch(NonCancellable) {
                LOG.error("Could not re-deploy Flink cluster for '{}' cleanly:", jobKey, t)
            }
            throw t
        }
    }

    private suspend fun executeShutdownPhase(flinkJob: FlinkJobCustomResource): FlinkJobCustomResource = withContext(Dispatchers.IO) {
        LOG.info("Shutting down Flink cluster for '{}'.", jobKey)
        setAsCompletedCoroutine.getAndSet(null)?.cancel()

        val deploymentExisted = deploymentMonitor.addedFlag.get()

        val phase = FlinkJobShutdownPhase(k8sClient, jobKey, flinkJob)
        val savepointPath = try {
            supervisorScope {
                phase.shutDownCleanly()
            }
        } catch (e: Exception) {
            LOG.error("Could not shut down Flink cluster for '{}' cleanly:", jobKey, e)
            null
        }

        // reset for new deployment
        if (deploymentExisted) {
            runInterruptible { deploymentMonitor.deletionLatch.get().await() }
            deploymentMonitor.deletionLatch.set(CountDownLatch(1))
            LOG.info("Flink cluster for '{}' shut down cleanly.", jobKey)
        }

        flinkJob.status.florkPhase = FlorkPhase.COMPLETED
        if (savepointPath != null) {
            flinkJob.status.knownSavepointPath = savepointPath
        }

        return@withContext patchStatus(flinkJob, false)
    }

    private suspend fun awaitDeploymentDeletionAndComplete(flinkJob: FlinkJobCustomResource) = withContext(Dispatchers.IO) {
        runInterruptible { deploymentMonitor.deletionLatch.get().await() }
        LOG.info("Flink job '{}' has either finished, failed, or been cancelled.", jobKey)

        setAsDeployedCoroutine.getAndSet(null)?.cancel()

        if (flinkJob.spec.policies?.deletion?.trigger == DeletionPolicy.Triggers.Termination) {
            LOG.info("Deleting CR for '{}' according to its deletion policy.", jobKey)
            crOperations.delete(flinkJob)
        } else {
            val patchedFlinkJob = crOperations.reloadResource(flinkJob)
            patchedFlinkJob.status.florkPhase = FlorkPhase.COMPLETED
            patchStatus(patchedFlinkJob)
            runInterruptible {
                CoroutineFlinkJobReconciler.maybeCleanHighAvailability(k8sClient, flinkJob, jobKey)
            }
        }

        launch(NonCancellable) {
            this@CoroutineFlinkJobReconcilerPhaser.cancel()
        }
    }

    // generation doesn't change with metadata or status updates
    private suspend fun patchStatus(flinkJob: FlinkJobCustomResource, updateGeneration: Boolean = true): FlinkJobCustomResource = withContext(Dispatchers.IO) {
        if (updateGeneration) {
            flinkJob.status.generationDuringLastTransition = observedGeneration
        }
        crOperations.patchStatus(flinkJob)
    }

    override fun onStopLeading() {
        setAsCompletedCoroutine.getAndSet(null)?.cancel()
        setAsDeployedCoroutine.getAndSet(null)?.cancel()
    }
}
