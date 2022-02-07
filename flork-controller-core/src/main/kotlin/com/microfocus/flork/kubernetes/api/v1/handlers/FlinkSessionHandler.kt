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

package com.microfocus.flork.kubernetes.api.v1.handlers

import com.microfocus.flork.kubernetes.api.v1.reconcilers.factories.CoroutineFlinkSessionReconcilerFactory
import com.microfocus.flork.kubernetes.api.v1.reconcilers.factories.FlinkSessionReconcilerFactory
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class FlinkSessionHandler private constructor(k8sClient: KubernetesClient) : ResourceEventHandler<com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource> {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(FlinkSessionHandler::class.java)

        @JvmField
        val RECONCILER_FACTORY = AtomicReference<FlinkSessionReconcilerFactory>(CoroutineFlinkSessionReconcilerFactory())

        @JvmStatic
        fun createInformerWithHandler(k8sClient: KubernetesClient, tenant: String, namespace: String, resyncPeriodSeconds: Long): SharedIndexInformer<com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource> {
            val handler = FlinkSessionHandler(k8sClient)
            return HandlerUtils.getPopulatedInformer(tenant, namespace, resyncPeriodSeconds, com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource::class.java, k8sClient, handler, handler.lister)
        }
    }

    private val lister: AtomicReference<Lister<com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource>?> = AtomicReference()
    private val reconciler = RECONCILER_FACTORY.get().create(k8sClient, lister)

    // must be non-blocking, at least initially, otherwise inform(handler, resync) doesn't return
    override fun onAdd(obj: com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource) {
        val key = Cache.metaNamespaceKeyFunc(obj)
        LOG.info("Flink session added with creation timestamp {} and generation {}: {}",
                obj.metadata.creationTimestamp, obj.metadata.generation, key)

        reconciler.reconcile(obj)
    }

    override fun onUpdate(oldObj: com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource, newObj: com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource) {
        reconciler.reconcile(newObj)
    }

    override fun onDelete(obj: com.microfocus.flork.kubernetes.api.v1.model.FlinkSessionCustomResource, deletedFinalStateUnknown: Boolean) {
        LOG.info("Flink sesion deleted in namespace {}: {}", obj.metadata.namespace, obj.metadata.name)
        reconciler.delete(obj)
    }
}
