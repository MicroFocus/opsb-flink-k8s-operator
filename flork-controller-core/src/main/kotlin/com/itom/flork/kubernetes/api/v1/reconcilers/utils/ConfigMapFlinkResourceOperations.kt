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

package com.itom.flork.kubernetes.api.v1.reconcilers.utils

import com.itom.flork.kubernetes.api.utils.JsonPatchOperation
import com.itom.flork.kubernetes.api.v1.handlers.ConfigMapFlinkJobHandler
import com.itom.flork.kubernetes.api.v1.handlers.HandlerUtils
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory

class ConfigMapFlinkResourceOperations<Spec, Status, T : CustomResource<Spec, Status>>(private val k8sClient: KubernetesClient) : FlinkResourceOperations<Spec, Status, T> {
    companion object {
        private val LOG = LoggerFactory.getLogger(ConfigMapFlinkResourceOperations::class.java)
    }

    override suspend fun reloadResource(flinkResource: T): T {
        return flinkResource
    }

    override suspend fun patchStatus(flinkResource: T): T {
        val statusConfigMapName = ConfigMapFlinkJobHandler.getStatusConfigMapName(flinkResource)
        runInterruptible {
            val patchContext = PatchContext.Builder()
                    .withPatchType(PatchType.JSON)
                    .build()
            val jsonPatchOperation = JsonPatchOperation(
                    "replace",
                    "/data/${ConfigMapFlinkJobHandler.STATUS_CM_STATUS_KEY}",
                    HandlerUtils.MAPPER.writeValueAsString(flinkResource.status)
            )
            patchStatusWithRetries(
                    statusConfigMapName,
                    patchContext,
                    ConfigMapFlinkJobHandler.JSON_MAPPER.writeValueAsString(listOf(jsonPatchOperation))
            )
        }
        return flinkResource
    }

    private tailrec fun patchStatusWithRetries(name: String, patchContext: PatchContext, diff: String) {
        try {
            val patched = k8sClient.configMaps()
                    .inNamespace(k8sClient.namespace)
                    .withName(name)
                    .patch(patchContext, diff)

            if (patched == null) {
                LOG.warn("Could not patch CM '{}', retrying.", name)
            } else {
                return
            }
        } catch (e: KubernetesClientException) {
            LOG.warn("Could not patch status, retrying:", e)
        }

        if (Thread.currentThread().isInterrupted) {
            LOG.warn("Interrupted.")
            throw InterruptedException()
        } else {
            Thread.sleep(500L)
        }

        patchStatusWithRetries(name, patchContext, diff)
    }

    override suspend fun delete(flinkResource: T): Boolean? {
        return k8sClient.configMaps()
                .inNamespace(flinkResource.metadata.namespace)
                .withName(flinkResource.metadata.name)
                .delete()
    }
}
