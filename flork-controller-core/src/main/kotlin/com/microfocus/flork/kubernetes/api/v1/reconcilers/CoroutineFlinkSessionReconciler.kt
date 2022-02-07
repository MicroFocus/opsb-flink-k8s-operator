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

package com.microfocus.flork.kubernetes.api.v1.reconcilers

import com.microfocus.flork.kubernetes.api.v1.reconcilers.phasers.CoroutineFlinkSessionReconcilerPhaser
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference

class CoroutineFlinkSessionReconciler(private val k8sClient: KubernetesClient, private val lister: AtomicReference<Lister<com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource>?>) : FlinkSessionReconciler {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(CoroutineFlinkSessionReconciler::class.java)

        private val COROUTINE_SCOPE = AtomicReference(createCoroutineScope())
        private val RECONCILER_STATES: ConcurrentMap<String, CoroutineFlinkSessionReconcilerPhaser> = ConcurrentHashMap()

        private fun createCoroutineScope() = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            LOG.error("Error during FlinkSession reconciliation:", throwable)
        })

        @JvmStatic
        fun resetScope() {
            RECONCILER_STATES.forEach { it.value.cancel() }
            COROUTINE_SCOPE.getAndSet(createCoroutineScope()).cancel()
            RECONCILER_STATES.clear()
        }
    }

    override fun reconcile(flinkSession: com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource) {
        COROUTINE_SCOPE.get().launch {
            val key = Cache.metaNamespaceKeyFunc(flinkSession)
            val state = RECONCILER_STATES.merge(key, CoroutineFlinkSessionReconcilerPhaser(this, k8sClient, lister, key)) { old, new ->
                when {
                    old.isActive() -> {
                        new.cancel()
                        old
                    }
                    old.wasGenerationObserved(flinkSession) -> {
                        new.cancel()
                        old
                    }
                    else -> {
                        new
                    }
                }
            }
            if (state?.start(this, key) == true || state?.isActive() == true) {
                state.channel.send(flinkSession)
            }
        }
    }

    override fun delete(flinkSession: com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource) {
        val key = Cache.metaNamespaceKeyFunc(flinkSession)
        RECONCILER_STATES.remove(key)?.cancel()
        k8sClient.apps().deployments()
                .inNamespace(flinkSession.metadata.namespace)
                .withName(flinkSession.metadata.name)
                .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                .delete()
    }
}
