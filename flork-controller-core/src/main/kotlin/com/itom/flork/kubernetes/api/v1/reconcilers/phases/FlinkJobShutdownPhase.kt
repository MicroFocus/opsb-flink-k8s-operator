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

package com.itom.flork.kubernetes.api.v1.reconcilers.phases

import com.itom.flork.kubernetes.api.utils.FlinkApplicationClusterDeployer
import com.itom.flork.kubernetes.api.utils.FlinkConfUtils
import com.itom.flork.kubernetes.api.utils.FlorkUtils
import com.itom.flork.kubernetes.api.v1.model.FlinkJobCustomResource
import com.itom.flork.kubernetes.api.v1.model.FlorkPhase
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.apache.flink.client.program.ClusterClient
import org.apache.flink.configuration.CheckpointingOptions
import org.apache.flink.configuration.GlobalConfiguration
import org.slf4j.LoggerFactory
import kotlin.io.path.createTempDirectory

class FlinkJobShutdownPhase(
        private val k8sClient: KubernetesClient,
        private val jobKey: String,
        private val flinkJob: FlinkJobCustomResource
) {
    companion object {
        private val LOG = LoggerFactory.getLogger(FlinkJobShutdownPhase::class.java)
    }

    suspend fun shutDownCleanly(): String? = withContext(Dispatchers.IO) {
        val savepointPath = if (flinkJob.status.florkPhase == FlorkPhase.DEPLOYED) {
            LOG.info("Checking if savepoint for job '{}' should be triggered.", jobKey)
            try {
                supervisorScope {
                    shutDownWithSavepoint()
                }
            } catch (e: Exception) {
                LOG.error("Could not trigger savepoint for '{}':", jobKey, e)
                null
            }
        } else {
            null
        }

        runInterruptible {
            k8sClient.apps().deployments()
                    .inNamespace(flinkJob.metadata.namespace)
                    .withName(flinkJob.metadata.name)
                    .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                    .delete()
        }

        return@withContext savepointPath
    }

    private suspend fun shutDownWithSavepoint() = coroutineScope {
        // recover configuration for REST client
        val tempConfPath = runInterruptible {
            createTempDirectory(prefix = jobKey.replace("/", "_") + "_").also {
                FlinkConfUtils.writeConfYamlTo(flinkJob, it)
            }
        }

        val flinkConfig = try {
            runInterruptible {
                GlobalConfiguration.loadConfiguration(tempConfPath.toString())
            }
        } finally {
            FlinkConfUtils.cleanFiles(tempConfPath)
        }

        val withSavepoint = flinkConfig.contains(CheckpointingOptions.SAVEPOINT_DIRECTORY)
        if (!withSavepoint) {
            LOG.info("No savepoint directory configured for '{}'.")
        }

        val factory = FlinkApplicationClusterDeployer.getClusterClientFactory(flinkConfig)
        val flinkClusterDescriptor = FlorkUtils.getDescriptorWithTlsIfNeeded(jobKey, factory, flinkConfig,
                flinkJob.spec.florkConf?.preferClusterInternalService ?: true)

        return@coroutineScope flinkClusterDescriptor.use { descriptor ->
            descriptor.retrieve(flinkJob.metadata.name).clusterClient.use { flinkClient ->
                sendSavepointCommand(flinkClient, withSavepoint)
            }
        }
    }

    private suspend fun sendSavepointCommand(flinkClient: ClusterClient<String>, withSavepoint: Boolean) = coroutineScope {
        val job = flinkClient.listJobs().await().firstOrNull()
        if (job == null) {
            LOG.warn("Could not find job corresponding to '{}' in its job manager.", jobKey)
            return@coroutineScope null
        }
        return@coroutineScope if (withSavepoint) {
            LOG.info("Stopping '{}' with savepoint.", jobKey)
            val flag = flinkJob.spec.policies?.savepoint?.advanceToEndOfEventTime ?: false
            flinkClient.stopWithSavepoint(job.jobId, flag, null).await()
        } else {
            LOG.info("Cancelling '{}' without savepoint.", jobKey)
            flinkClient.cancel(job.jobId).await()
            null
        }
    }
}
