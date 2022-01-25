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

package com.itom.flork.kubernetes.api.v1.reconcilers

import com.itom.flork.kubernetes.api.v1.model.FlinkJobCustomResource
import com.itom.flork.kubernetes.api.v1.reconcilers.phasers.CoroutineFlinkJobPhaserWithoutCRD
import com.itom.flork.kubernetes.api.v1.reconcilers.phasers.CoroutineFlinkJobReconcilerPhaser
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.*
import org.apache.flink.configuration.HighAvailabilityOptions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference

class CoroutineFlinkJobReconciler(
        private val k8sClient: KubernetesClient,
        private val lister: AtomicReference<Lister<FlinkJobCustomResource>?>,
        private val crdBased: Boolean
) : FlinkJobReconciler {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(CoroutineFlinkJobReconciler::class.java)

        private val COROUTINE_SCOPE = AtomicReference(createCoroutineScope())
        private val RECONCILER_STATES: ConcurrentMap<String, CoroutineFlinkJobReconcilerPhaser> = ConcurrentHashMap()

        private fun createCoroutineScope() = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            LOG.error("Error during FlinkJob reconciliation:", throwable)
        })

        @JvmStatic
        fun resetScope() {
            RECONCILER_STATES.forEach { it.value.cancel() }
            COROUTINE_SCOPE.getAndSet(createCoroutineScope()).cancel()
            RECONCILER_STATES.clear()
        }

        fun maybeCleanHighAvailability(k8sClient: KubernetesClient, flinkJob: FlinkJobCustomResource, key: String?) {
            if (flinkJob.spec.flinkConf?.containsKey(HighAvailabilityOptions.HA_MODE.key()) == true && flinkJob.spec.florkConf?.cleanHighAvailability == true) {
                LOG.info("Cleaning HA config maps for '{}'.", key)
                val labels = mapOf(
                        "app" to flinkJob.metadata.name,
                        "configmap-type" to "high-availability",
                        "type" to "flink-native-kubernetes"
                )
                val cmOperation = k8sClient.configMaps()
                        .inNamespace(flinkJob.metadata.namespace)
                        .withLabels(labels)

                var list = cmOperation.list()
                while (list.items?.isNotEmpty() == true) {
                    LOG.debug("Deleting {} config map(s).", list.items?.size)
                    cmOperation.delete()
                    Thread.sleep(100L)
                    list = cmOperation.list()
                }
            }
        }
    }

    override fun reconcile(flinkJob: FlinkJobCustomResource) {
        COROUTINE_SCOPE.get().launch {
            val key = Cache.metaNamespaceKeyFunc(flinkJob)
            val phaserCandidate = if (crdBased) {
                CoroutineFlinkJobReconcilerPhaser(this, k8sClient, lister, key)
            } else {
                CoroutineFlinkJobPhaserWithoutCRD(this, k8sClient, lister, key)
            }
            val state = RECONCILER_STATES.merge(key, phaserCandidate) { old, new ->
                when {
                    old.isActive() -> {
                        new.cancel()
                        old
                    }
                    old.wasGenerationObserved(flinkJob) -> {
                        new.cancel()
                        old
                    }
                    else -> {
                        new
                    }
                }
            }
            if (state?.start(this, key) == true || state?.isActive() == true) {
                state.channel.send(flinkJob)
            }
        }
    }

    override fun delete(flinkJob: FlinkJobCustomResource) {
        val key = Cache.metaNamespaceKeyFunc(flinkJob)
        RECONCILER_STATES.remove(key)?.cancel()

        k8sClient.apps().deployments()
                .inNamespace(flinkJob.metadata.namespace)
                .withName(flinkJob.metadata.name)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()

        maybeCleanHighAvailability(k8sClient, flinkJob, key)
    }
}
