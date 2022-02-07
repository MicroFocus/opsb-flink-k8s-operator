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

import com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource
import com.microfocus.flork.kubernetes.api.v1.reconcilers.factories.CoroutineFlinkJobReconcilerFactory
import com.microfocus.flork.kubernetes.api.v1.reconcilers.factories.FlinkJobReconcilerFactory
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class FlinkJobHandler private constructor(k8sClient: KubernetesClient, reconcilerFactory: FlinkJobReconcilerFactory) : ResourceEventHandler<FlinkJobCustomResource> {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(FlinkJobHandler::class.java)

        /**
         * This version assumes the handler will be registered with Kubernetes by the caller.
         */
        @JvmStatic
        fun create(k8sClient: KubernetesClient, reconcilerFactory: FlinkJobReconcilerFactory): FlinkJobHandler {
            return FlinkJobHandler(k8sClient, reconcilerFactory)
        }

        @JvmStatic
        fun createInformerWithHandler(k8sClient: KubernetesClient, tenant: String, namespace: String, resyncPeriodSeconds: Long): SharedIndexInformer<FlinkJobCustomResource> {
            val handler = create(k8sClient, CoroutineFlinkJobReconcilerFactory())
            return HandlerUtils.getPopulatedInformer(tenant, namespace, resyncPeriodSeconds, FlinkJobCustomResource::class.java, k8sClient, handler, handler.lister)
        }
    }

    private val lister: AtomicReference<Lister<FlinkJobCustomResource>?> = AtomicReference()
    private val reconciler = reconcilerFactory.create(k8sClient, lister)

    // must be non-blocking, at least initially, otherwise inform(handler, resync) doesn't return
    override fun onAdd(obj: FlinkJobCustomResource) {
        val key = Cache.metaNamespaceKeyFunc(obj)
        LOG.info("Flink job added with creation timestamp {} and generation {}: {}",
                obj.metadata.creationTimestamp, obj.metadata.generation, key)

        reconciler.reconcile(obj)
    }

    override fun onUpdate(oldObj: FlinkJobCustomResource?, newObj: FlinkJobCustomResource) {
        reconciler.reconcile(newObj)
    }

    override fun onDelete(obj: FlinkJobCustomResource, deletedFinalStateUnknown: Boolean) {
        LOG.info("Flink job deleted in namespace {}: {}", obj.metadata.namespace, obj.metadata.name)
        reconciler.delete(obj)
    }
}
