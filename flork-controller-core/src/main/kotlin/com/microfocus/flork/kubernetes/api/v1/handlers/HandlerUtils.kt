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

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Lister
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

object HandlerUtils {
    private val LOG: Logger = LoggerFactory.getLogger(HandlerUtils::class.java)

    val MAPPER: ObjectMapper = ObjectMapper(
            YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    )

    /**
     * Handler's methods must be non-blocking.
     */
    @JvmStatic
    fun <T : HasMetadata> getPopulatedInformer(
            tenant: String,
            namespace: String,
            resyncPeriodSeconds: Long,
            clazz: Class<T>,
            k8sClient: KubernetesClient,
            handler: ResourceEventHandler<T>,
            listerReference: AtomicReference<Lister<T>?>
    ): SharedIndexInformer<T> {
        val operation = k8sClient.resources(clazz)

        val informer = if (namespace == "*") {
            LOG.info("Creating {} informer and handler for tenant '{}' in ALL namespaces.", clazz.simpleName, tenant)
            operation.inAnyNamespace()
                    .inform(handler, resyncPeriodSeconds * 1000L)
        } else {
            LOG.info("Creating {} informer and handler for tenant '{}' in namespace '{}'.", clazz.simpleName, tenant, namespace)
            operation.inNamespace(namespace)
                    .inform(handler, resyncPeriodSeconds * 1000L)
        }

        listerReference.set(Lister(informer.indexer))
        return informer
    }

    fun getPopulatedInformer(
            k8sClient: KubernetesClient,
            withLabel: String,
            withoutLabel: String,
            resyncPeriodSeconds: Long,
            handler: ResourceEventHandler<ConfigMap>,
            listerReference: AtomicReference<Lister<ConfigMap>?>
    ): SharedIndexInformer<ConfigMap> {
        val informer = k8sClient.resources(ConfigMap::class.java)
                .inNamespace(k8sClient.namespace)
                .withLabel(withLabel)
                .withoutLabel(withoutLabel)
                .inform(handler, resyncPeriodSeconds * 1000L)
        listerReference.set(Lister(informer.indexer))
        return informer
    }

    @Throws(JsonParseException::class, JsonMappingException::class)
    inline fun <reified T> unmarshall(yaml: String?): T {
        return MAPPER.readValue(yaml, T::class.java)
    }
}
