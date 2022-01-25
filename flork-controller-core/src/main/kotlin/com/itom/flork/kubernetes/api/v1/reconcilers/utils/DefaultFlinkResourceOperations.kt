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

import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class DefaultFlinkResourceOperations<Spec, Status, T : CustomResource<Spec, Status>>(
        k8sClient: KubernetesClient,
        clazz: Class<T>,
        private val lister: AtomicReference<Lister<T>?>
) : FlinkResourceOperations<Spec, Status, T> {
    companion object {
        private val LOG = LoggerFactory.getLogger(DefaultFlinkResourceOperations::class.java)
    }

    private val crOperation = k8sClient.resources(clazz)

    override suspend fun reloadResource(flinkResource: T): T = withContext(Dispatchers.IO) {
        runInterruptible {
            val concreteLister = waitForListerToBePopulated()
            concreteLister.namespace(flinkResource.metadata.namespace).get(flinkResource.metadata.name)
        }
    }

    private fun waitForListerToBePopulated(): Lister<T> {
        while (true) {
            val l = lister.get()
            if (l == null) {
                Thread.sleep(1_000L)
            } else {
                return l
            }
        }
    }

    // generation doesn't change with metadata or status updates
    override suspend fun patchStatus(flinkResource: T): T = withContext(Dispatchers.IO) {
        runInterruptible {
            patchStatusWithRetries(flinkResource)
        }
    }

    private tailrec fun patchStatusWithRetries(flinkResource: T): T {
        try {
            return crOperation
                    .inNamespace(flinkResource.metadata.namespace)
                    .withName(flinkResource.metadata.name)
                    .patchStatus(flinkResource)
        } catch (e: KubernetesClientException) {
            LOG.warn("Could not patch status, retrying:", e)
        }

        if (Thread.currentThread().isInterrupted) {
            LOG.warn("Interrupted.")
            throw InterruptedException()
        }

        val concreteLister = waitForListerToBePopulated()
        val cr = concreteLister.namespace(flinkResource.metadata.namespace)?.get(flinkResource.metadata.name) ?: flinkResource
        cr.status = flinkResource.status
        return patchStatusWithRetries(cr)
    }

    override suspend fun delete(flinkResource: T): Boolean? = withContext(Dispatchers.IO) {
        runInterruptible {
            crOperation
                    .inNamespace(flinkResource.metadata.namespace)
                    .withName(flinkResource.metadata.name)
                    .delete()
        }
    }
}
