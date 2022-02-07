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

package com.microfocus.flork.kubernetes.api.v1.reconcilers.phases

import com.microfocus.flork.kubernetes.api.utils.FlinkConfUtils
import com.microfocus.flork.kubernetes.api.utils.FlorkUtils
import com.microfocus.flork.kubernetes.api.v1.model.FlorkPhase
import com.microfocus.flork.kubernetes.api.v1.reconcilers.utils.FlinkResourceDeploymentMonitor
import com.microfocus.flork.kubernetes.api.v1.reconcilers.utils.FlinkResourceOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.apache.flink.api.common.JobStatus
import org.apache.flink.client.deployment.ClusterDeploymentException
import org.apache.flink.client.program.ClusterClient
import org.apache.flink.configuration.Configuration
import org.apache.flink.configuration.GlobalConfiguration
import org.apache.flink.configuration.IllegalConfigurationException
import org.apache.flink.configuration.SecurityOptions
import org.apache.flink.runtime.jobgraph.SavepointConfigOptions
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory

class FlinkJobCreatePhase(
    private val jobKey: String,
    private val deploymentMonitor: FlinkResourceDeploymentMonitor,
    private val flinkJob: com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource,
    private val crOperations: FlinkResourceOperations<*, *, com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource>,
    private val setAsDeployedCoroutine: AtomicReference<Job?>
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(FlinkJobCreatePhase::class.java)
    }
    
    private val observedGeneration = flinkJob.metadata.generation

    suspend fun performInitialDeployment(backgroundTaskScope: CoroutineScope) = withContext(Dispatchers.IO) {
        LOG.info("Preparing deployment of '{}'.", jobKey)
        val tempConfPath = runInterruptible {
            createTempDirectory(prefix = jobKey.replace("/", "_") + "_")
        }

        try {
            return@withContext performInitialDeployment(backgroundTaskScope, tempConfPath)
        } finally {
            FlinkConfUtils.cleanFiles(tempConfPath)
        }
    }

    private suspend fun performInitialDeployment(backgroundTaskScope: CoroutineScope, confPath: Path) = coroutineScope {
        FlinkConfUtils.prepareConfFilesFromSpec(flinkJob, confPath)

        val flinkConfig = runInterruptible {
            GlobalConfiguration.loadConfiguration(confPath.toString())
        }

        flinkJob.status.knownSavepointPath?.let { sp ->
            flinkConfig.set(SavepointConfigOptions.SAVEPOINT_PATH, sp)
        }

        deployFlinkCluster(flinkConfig)

        patchStatus(crOperations.reloadResource(flinkJob).apply {
            status.florkPhase = FlorkPhase.DEPLOYING
        })

        val backgroundCoroutine = backgroundTaskScope.launch {
            supervisorScope {
                val factory = com.microfocus.flork.kubernetes.api.utils.FlinkApplicationClusterDeployer.getClusterClientFactory(flinkConfig)
                val flinkClusterDescriptor = FlorkUtils.getDescriptorWithTlsIfNeeded(jobKey, factory, flinkConfig,
                        flinkJob.spec.florkConf?.preferClusterInternalService ?: true)
                flinkClusterDescriptor.use { descriptor ->
                    descriptor.retrieve(flinkJob.metadata.name).clusterClient.use { flinkClient ->
                        waitForRunningJob(flinkJob, flinkClient)
                    }
                }
            }
        }

        setAsDeployedCoroutine.getAndSet(backgroundCoroutine)?.cancel()
    }

    private suspend fun deployFlinkCluster(flinkConfig: Configuration?) = coroutineScope {
        var seconds = 1L
        while (true) {
            try {
                supervisorScope {
                    runInterruptible {
                        com.microfocus.flork.kubernetes.api.utils.FlinkApplicationClusterDeployer.deployWithConfigFile(flinkConfig, flinkJob.spec.jobClassName,
                                flinkJob.spec.jobArgs ?: arrayOf())
                    }
                }
                break
            } catch (e: ClusterDeploymentException) {
                if (e.message?.contains("already exists") == true) {
                    LOG.warn("Not all Kubernetes resources created for '{}' have been cleaned, retrying.", jobKey)
                    runInterruptible {
                        if (deploymentMonitor.deletionLatch.get().await(seconds, TimeUnit.SECONDS)) {
                            deploymentMonitor.deletionLatch.set(CountDownLatch(1))
                        }
                    }
                    if (seconds < 15L) {
                        seconds *= 2L
                    }
                } else {
                    throw e
                }
            } catch (e: Exception) {
                checkIfDeploymentActuallyFailed(flinkJob, SecurityOptions.isRestSSLEnabled(flinkConfig), e)
                break
            }
        }
    }

    private suspend fun checkIfDeploymentActuallyFailed(flinkJob: com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource, restTls: Boolean, e: Exception) = coroutineScope {
        if (e !is InterruptedException && deploymentMonitor.addedFlag.get()) {
            if (restTls) {
                var ex: Throwable = e
                while (ex.cause != null) {
                    ex = ex.cause ?: break
                }
                if (ex is IllegalConfigurationException && ex.message?.contains(SecurityOptions.SSL_KEYSTORE.key()) == true) {
                    LOG.debug("Job '{}' enabled TLS for REST but didn't set stores, they are likely dynamically set in the container. Assuming they trust Flork's certificates.", jobKey)
                    return@coroutineScope
                }
            }
            LOG.warn("Cluster deployer for '{}' failed but deployment still succeeded:", jobKey, e)
            return@coroutineScope
        }

        LOG.error("Could not deploy Flink cluster for '{}'.", jobKey)

        flinkJob.status.florkPhase = FlorkPhase.FAILED
        try {
            patchStatus(flinkJob)
        } catch (t: Throwable) {
            LOG.error("Could not patch status of failed job '{}':", jobKey, t)
        }

        throw e
    }
    
    private suspend fun waitForRunningJob(flinkJob: com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource, flinkClient: ClusterClient<String>) = coroutineScope {
        while (true) {
            try {
                val jobs = flinkClient.listJobs().await()
                val job = jobs.firstOrNull()
                if (job == null) {
                    // I think this never happens
                    LOG.trace("No jobs registered yet for '{}'.", jobKey)
                    delay(2_000L)
                    continue
                }

                LOG.trace("Job '{}' with ID={} has state={}.", jobKey, job.jobId, job.jobState)
                if (job.jobState == JobStatus.CREATED || job.jobState == JobStatus.RUNNING) {
                    val reloadedFlinkJob = crOperations.reloadResource(flinkJob)
                    reloadedFlinkJob.status.knownSavepointPath?.let { sp ->
                        flinkClient.disposeSavepoint(sp).await()
                    }
                    reloadedFlinkJob.status.apply {
                        florkPhase = FlorkPhase.DEPLOYED
                        knownSavepointPath = null
                    }
                    patchStatus(reloadedFlinkJob)
                    break
                } else {
                    delay(2_000L)
                }
            } catch (e: Exception) {
                if (e is InterruptedException || e is CancellationException) {
                    LOG.debug("Fetching Flink status of '{}' was interrupted/cancelled:", jobKey, e)
                    throw e
                }
                LOG.warn("Exception while trying to fetch Flink status of '{}':", jobKey, e)
                delay(2_000L)
            }
        }
    }

    private suspend fun patchStatus(flinkJob: com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource): com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource = coroutineScope {
        flinkJob.status.generationDuringLastTransition = observedGeneration
        crOperations.patchStatus(flinkJob)
    }
}
