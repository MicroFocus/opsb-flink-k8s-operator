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

import com.fasterxml.jackson.databind.ObjectMapper
import com.microfocus.flork.kubernetes.api.constants.FlorkConstants
import com.microfocus.flork.kubernetes.api.constants.RuntimeConstants
import com.microfocus.flork.kubernetes.api.utils.JsonPatchOperation
import com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource
import com.microfocus.flork.kubernetes.api.v1.model.FlinkJobStatus
import com.microfocus.flork.kubernetes.api.v1.reconcilers.factories.CoroutineFlinkJobReconcilerFactoryWithoutCRD
import com.microfocus.flork.kubernetes.api.v1.reconcilers.phasers.AbstractReconcilerPhaser
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

typealias StatusConfigMap = ConfigMap

class ConfigMapFlinkJobHandler private constructor(private val k8sClient: KubernetesClient) : ResourceEventHandler<ConfigMap> {
    companion object {
        private val LOG = LoggerFactory.getLogger(ConfigMapFlinkJobHandler::class.java)

        private const val STATUS_CM_SUFFIX = "flork-status"
        private const val STATUS_CM_META_KEY = "crMetadata"

        const val STATUS_CM_STATUS_KEY = "crStatus"

        private val STATUS_CMS: ConcurrentMap<String, StatusConfigMap> = ConcurrentHashMap()

        @JvmField
        val JSON_MAPPER = ObjectMapper()

        @JvmStatic
        fun createInformersWithHandler(k8sClient: KubernetesClient, resyncPeriodSeconds: Long): Triple<ConfigMapFlinkJobHandler, SharedIndexInformer<ConfigMap>, SharedIndexInformer<StatusConfigMap>> {
            val handler = ConfigMapFlinkJobHandler(k8sClient)
            val informer = HandlerUtils.getPopulatedInformer(
                    k8sClient,
                    FlorkConstants.FLORK_FJ_CM_LABEL,
                    FlorkConstants.FLORK_FS_CM_LABEL,
                    resyncPeriodSeconds,
                    handler,
                    handler.lister
            )
            return Triple(handler, informer, handler.innerInformer)
        }

        @JvmStatic
        fun getStatusConfigMapName(hasMeta: HasMetadata): String {
            return "${hasMeta.metadata.name}-$STATUS_CM_SUFFIX"
        }

        private fun getExistingConfigMap(k8sClient: KubernetesClient, name: String): ConfigMap? {
            return k8sClient.configMaps()
                    .inNamespace(k8sClient.namespace)
                    .withName(name)
                    .get()
        }

        private fun computeNameFromStatusConfigMap(cmName: String): String {
            return cmName.substringBeforeLast("-$STATUS_CM_SUFFIX")
        }

        private fun createStatusConfigMapIfNeeded(k8sClient: KubernetesClient, name: String, cm: ConfigMap) {
            val existing = getExistingConfigMap(k8sClient, name)
            if (existing != null) {
                STATUS_CMS.putIfAbsent(name, existing)
                return
            }

            LOG.info("Creating status CM for {}.", name)

            val crMeta = ObjectMetaBuilder()
                    .withNamespace(k8sClient.namespace)
                    .withName(cm.metadata.name)
                    .withCreationTimestamp(cm.metadata.creationTimestamp)
                    .withGeneration(0L)
                    .build()

            val crStatus = FlinkJobStatus()

            val statusConfigMap = ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .addToLabels(FlorkConstants.FLORK_FJ_SCM_LABEL, "true")
                    .addToLabels(FlorkConstants.METADATA_CM_VALIDITY_LABEL, "false")
                    .endMetadata()
                    .addToData(STATUS_CM_META_KEY, HandlerUtils.MAPPER.writeValueAsString(crMeta))
                    .addToData(STATUS_CM_STATUS_KEY, HandlerUtils.MAPPER.writeValueAsString(crStatus))
                    .build()

            try {
                k8sClient.configMaps()
                        .inNamespace(k8sClient.namespace)
                        .create(statusConfigMap)
            } catch (e: Exception) {
                // could fail in HA but it's not critical, K8s handles the race condition
                if (e.message?.contains("already exists") != true) {
                    LOG.error("Could not create status CM:", e)
                }
            }

            STATUS_CMS[name] = statusConfigMap
        }

        private fun getUnderlyingResource(k8sClient: KubernetesClient, cm: ConfigMap): FlinkJobCustomResource {
            val resourceYaml = cm.data["customResource"] ?: throw IllegalArgumentException("Config map does not have key 'customResource'.")
            val cr = HandlerUtils.unmarshall<FlinkJobCustomResource>(resourceYaml)

            val statusConfigMap = getExistingConfigMap(k8sClient, getStatusConfigMapName(cm))
            if (statusConfigMap != null) {
                cr.metadata = HandlerUtils.unmarshall(statusConfigMap.data[STATUS_CM_META_KEY])
                cr.status = HandlerUtils.unmarshall(statusConfigMap.data[STATUS_CM_STATUS_KEY])

                if (cr.metadata.resourceVersion != cm.metadata.resourceVersion) {
                    cr.metadata.generation = cr.metadata.generation + 1L
                    cr.metadata.resourceVersion = cm.metadata.resourceVersion
                }
            }

            return cr
        }

        private fun maybeUpdateStatusConfigMap(k8sClient: KubernetesClient, name: String, cr: FlinkJobCustomResource): StatusConfigMap? {
            val crKey = if (cr.metadata.namespace == null) {
                LOG.warn("Namespace of {} was null.", cr.metadata.name)
                "${k8sClient.namespace}/${cr.metadata.name}"
            } else {
                Cache.metaNamespaceKeyFunc(cr)
            }

            if (isPodNotLeading(k8sClient, crKey)) {
                return null
            }

            val existing = getExistingConfigMap(k8sClient, name)
            val crMeta = existing
                    ?.data
                    ?.get(STATUS_CM_META_KEY)
                    ?.let { HandlerUtils.unmarshall<ObjectMeta>(it) }

            if (crMeta?.resourceVersion == cr.metadata?.resourceVersion && crMeta?.generation == cr.metadata?.generation) {
                return existing
            }

            val patchOperations = mutableListOf(
                    JsonPatchOperation(
                            "replace",
                            "/metadata/labels/${FlorkConstants.METADATA_CM_VALIDITY_LABEL}",
                            cr.metadata?.resourceVersion
                    ),
                    JsonPatchOperation(
                            "replace",
                            "/data/$STATUS_CM_META_KEY",
                            HandlerUtils.MAPPER.writeValueAsString(cr.metadata)
                    )
            ).apply {
                if (existing?.data?.containsKey("exception") == true) {
                    add(JsonPatchOperation("remove", "/data/exception"))
                }
            }

            return patchConfigMap(k8sClient, name, JSON_MAPPER.writeValueAsString(patchOperations))
        }

        private tailrec fun isPodNotLeading(k8sClient: KubernetesClient, crKey: String): Boolean {
            val leaseKey = AbstractReconcilerPhaser.getLeaseKey(crKey)
            val lease = k8sClient.leases()
                    .inNamespace(k8sClient.namespace)
                    .withName(leaseKey)
                    .get()

            if (lease == null) {
                LOG.warn("Attempted to update status of '{}' before a leader was elected, retrying.", crKey)
                Thread.sleep(500L)
                return isPodNotLeading(k8sClient, crKey)
            }

            val flag = lease.spec?.holderIdentity != RuntimeConstants.POD_NAME
            LOG.trace("Not leading? {}", flag)
            return flag
        }

        private fun patchConfigMap(k8sClient: KubernetesClient, name: String, cmDiff: String?): StatusConfigMap? {
            val patchContext = PatchContext.Builder()
                    .withPatchType(PatchType.JSON)
                    .build()

            return try {
                LOG.trace("Patching status CM '{}' with: {}", name, cmDiff)
                k8sClient.configMaps()
                        .inNamespace(k8sClient.namespace)
                        .withName(name)
                        .patch(patchContext, cmDiff)
            } catch (ee: Exception) {
                LOG.error("Could not update status CM:", ee)
                getExistingConfigMap(k8sClient, name)
            }
        }

        private fun updateStatusConfigMap(k8sClient: KubernetesClient, namespace: String, name: String, e: Exception): StatusConfigMap? {
            if (isPodNotLeading(k8sClient, "$namespace/${computeNameFromStatusConfigMap(name)}")) {
                return null
            }

            val existing = getExistingConfigMap(k8sClient, name)
            val op = if (existing?.data?.containsKey("exception") == true) "replace" else "add"

            val patchOperations = listOf(
                    JsonPatchOperation(
                            "replace",
                            "/metadata/labels/${FlorkConstants.METADATA_CM_VALIDITY_LABEL}",
                            "false"
                    ),
                    JsonPatchOperation(
                            op,
                            "/data/exception",
                            e.stackTraceToString().replace("\t", "  ")
                    )
            )

            return patchConfigMap(k8sClient, name, JSON_MAPPER.writeValueAsString(patchOperations))
        }
    }

    private val lister = AtomicReference<Lister<ConfigMap>?>()
    private val wrappedHandler = FlinkJobHandler.create(k8sClient, CoroutineFlinkJobReconcilerFactoryWithoutCRD())

    val paused = AtomicBoolean(false)

    private val innerInformer = k8sClient.configMaps()
            .inNamespace(k8sClient.namespace)
            .withLabel(FlorkConstants.FLORK_FJ_SCM_LABEL)
            .inform(StatusConfigMapHandler())

    override fun onAdd(obj: ConfigMap) {
        if (paused.get()) {
            return
        }

        val name = getStatusConfigMapName(obj)
        createStatusConfigMapIfNeeded(k8sClient, name, obj)

        STATUS_CMS.merge(name, obj) { _, _ ->
            try {
                val cr = getUnderlyingResource(k8sClient, obj)
                wrappedHandler.onAdd(cr)
                maybeUpdateStatusConfigMap(k8sClient, name, cr)
            } catch (e: Exception) {
                LOG.error("Could not process underlying resource:", e)
                updateStatusConfigMap(k8sClient, obj.metadata.namespace, name, e)
            }
        }
    }

    override fun onUpdate(oldObj: ConfigMap?, newObj: ConfigMap) {
        if (paused.get()) {
            return
        }

        val name = getStatusConfigMapName(newObj)
        createStatusConfigMapIfNeeded(k8sClient, name, newObj)

        val oldResource = oldObj?.let {
            try {
                getUnderlyingResource(k8sClient, it)
            } catch (e: Exception) {
                LOG.debug("Invalid oldObj:", e)
                null
            }
        }

        STATUS_CMS.merge(name, newObj) { _, _ ->
            try {
                val cr = getUnderlyingResource(k8sClient, newObj)
                maybeUpdateStatusConfigMap(k8sClient, name, cr).also {
                    wrappedHandler.onUpdate(oldResource, cr)
                }
            } catch (e: Exception) {
                LOG.error("Could not process update of underlying resource:", e)
                updateStatusConfigMap(k8sClient, newObj.metadata.namespace, name, e)
            }
        }
    }

    override fun onDelete(obj: ConfigMap, deletedFinalStateUnknown: Boolean) {
        if (paused.get()) {
            return
        }

        val name = getStatusConfigMapName(obj)
        try {
            wrappedHandler.onDelete(getUnderlyingResource(k8sClient, obj), deletedFinalStateUnknown)
        } catch (e: Exception) {
            LOG.error("Could not delete underlying CR:", e)
        } finally {
            k8sClient.configMaps()
                    .inNamespace(k8sClient.namespace)
                    .withName(name)
                    .delete()
        }
    }

    private fun onStatusUpdate(name: String) {
        getExistingConfigMap(k8sClient, name)?.let {
            onUpdate(it, it)
        }
    }

    inner class StatusConfigMapHandler : ResourceEventHandler<StatusConfigMap> {
        override fun onAdd(obj: StatusConfigMap?) {
            if (paused.get()) {
                return
            }

            LOG.trace("Status CM added: {}", obj?.metadata?.name)
        }

        override fun onUpdate(oldObj: StatusConfigMap?, newObj: StatusConfigMap) {
            if (paused.get()) {
                return
            }

            LOG.trace("Status CM updated: {}", newObj.metadata.name)
            val crKey = computeNameFromStatusConfigMap(newObj.metadata.name)
            if (isPodNotLeading(k8sClient, "${newObj.metadata.namespace}/$crKey")) {
                STATUS_CMS[newObj.metadata.name] = newObj
            }
            onStatusUpdate(crKey)
        }

        override fun onDelete(obj: StatusConfigMap, deletedFinalStateUnknown: Boolean) {
            if (paused.get()) {
                return
            }

            LOG.trace("Status CM deleted: {}", obj.metadata.name)
        }
    }
}
