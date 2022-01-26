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

package com.itom.flork.kubernetes.api.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.itom.flork.kubernetes.api.constants.FlorkConstants
import com.itom.flork.kubernetes.api.plugins.FlinkConfDecorator
import com.itom.flork.kubernetes.api.plugins.PodSpecDecorator
import com.itom.flork.kubernetes.api.plugins.PodSpecType
import com.itom.flork.kubernetes.api.v1.model.FlinkJobCustomResource
import com.itom.flork.kubernetes.api.v1.model.FlorkConf
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.client.informers.cache.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.SerializationUtils
import org.apache.flink.configuration.DeploymentOptions
import org.apache.flink.configuration.DeploymentOptionsInternal
import org.apache.flink.configuration.GlobalConfiguration
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions
import org.apache.flink.kubernetes.configuration.KubernetesDeploymentTarget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

object FlinkConfUtils {
    private val LOG: Logger = LoggerFactory.getLogger(FlinkConfUtils::class.java)

    private val defaultLogConfFiles = mutableMapOf(
            "log4j.properties" to "",
            "log4j-console.properties" to "",
            "logback.xml" to "",
            "logback-console.xml" to ""
    )

    @JvmField
    val MAPPER: ObjectMapper = ObjectMapper(
            YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    )

    init {
        for (entry in defaultLogConfFiles) {
            val stream = FlinkConfUtils::class.java.classLoader.getResourceAsStream("default/flink/conf/${entry.key}") ?: continue
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                entry.setValue(lines.joinToString(separator = System.lineSeparator()))
            }
        }
    }

    const val FLINK_CONF_DIR_KEY = "kubernetes.flink.conf.dir"
    
    suspend fun prepareConfFilesFromSpec(flinkJob: FlinkJobCustomResource, confPath: Path) = withContext(Dispatchers.IO) {
        runInterruptible {
            val jobManagerPodMeta = flinkJob.spec.jobManagerPodMeta ?: ObjectMeta()
            val jobManagerPodSpec = flinkJob.spec.jobManagerPodSpec ?: PodSpec()
            val taskManagerPodSpec = flinkJob.spec.taskManagerPodSpec ?: SerializationUtils.clone(jobManagerPodSpec)

            val desiredFlinkConfPath = writeConfYamlTo(flinkJob, confPath)
            val podSpecDecorators = ServiceLoader.load(PodSpecDecorator::class.java).sortedBy { it.priority() }

            Files.newOutputStream(confPath.resolve(templateFileName(PodSpecType.JOB_MANAGER))).use { os ->
                var podSpec = jobManagerPodSpec
                for (decorator in podSpecDecorators) {
                    LOG.debug("Decorating job manager's pod spec with {}.", decorator)
                    podSpec = decorator.decorate(podSpec, jobManagerPodMeta, flinkJob.spec.florkConf)
                }
                val pod = getFlinkPodTemplate(flinkJob, podSpec, desiredFlinkConfPath).apply {
                    val annotations = jobManagerPodMeta.annotations ?: mutableMapOf()
                    annotations["${FlorkConstants.CRD_GROUP}/epoch"] = System.currentTimeMillis().toString()

                    metadata.annotations = annotations
                    metadata.labels = jobManagerPodMeta.labels
                }
                LOG.trace("Writing job manager's pod template with: {}", pod)
                MAPPER.writeValue(os, pod)
            }

            Files.newOutputStream(confPath.resolve(templateFileName(PodSpecType.TASK_MANAGER))).use { os ->
                var podSpec = taskManagerPodSpec
                for (decorator in podSpecDecorators) {
                    LOG.debug("Decorating task manager's pod spec with {}.", decorator)
                    podSpec = decorator.decorate(podSpec, flinkJob.spec.florkConf)
                }
                val pod = getFlinkPodTemplate(flinkJob, podSpec, desiredFlinkConfPath)
                LOG.trace("Writing task manager's pod template with: {}", pod)
                MAPPER.writeValue(os, pod)
            }

            processAdditionalConfFiles(Cache.metaNamespaceKeyFunc(flinkJob), flinkJob.spec.additionalConfFiles, confPath)
            LOG.debug("Populated {} with Flink configuration files.", confPath)
        }
    }

    @JvmStatic
    fun writeConfYamlTo(flinkJob: FlinkJobCustomResource, confPath: Path): String {
        val desiredFlinkConfPath: String
        Files.newOutputStream(confPath.resolve(GlobalConfiguration.FLINK_CONF_FILENAME)).use { os ->
            val flinkConf = decorateConsideringMetadata(flinkJob.spec.flinkConf, confPath.toString(), flinkJob, flinkJob.spec.florkConf)
            desiredFlinkConfPath = flinkConf.getOrDefault(FLINK_CONF_DIR_KEY, "/opt/flink/conf").toString()
            if (flinkJob.spec.florkConf.shadowConfigFiles) {
                flinkConf[FLINK_CONF_DIR_KEY] = FlorkConstants.FLORK_CONF_DIR
            }
            LOG.trace("Writing {} with: {}", GlobalConfiguration.FLINK_CONF_FILENAME, flinkConf)
            MAPPER.writeValue(os, flinkConf)
        }
        return desiredFlinkConfPath
    }

    @JvmStatic
    private fun decorateConsideringMetadata(map: MutableMap<String, Any>?, confDir: String, resource: HasMetadata, florkConf: FlorkConf): MutableMap<String, Any> {
        var decorated = when (map) {
            null -> {
                mutableMapOf()
            }
            !is Serializable -> {
                map
            }
            else -> {
                SerializationUtils.clone(map)
            }
        }

        for (decorator in ServiceLoader.load(FlinkConfDecorator::class.java).sortedBy { it.priority() }) {
            LOG.debug("Decorating Flink conf with {}.", decorator)
            decorated = decorator.decorate(decorated, florkConf)
        }

        decorated.putIfAbsent("jobmanager.memory.process.size", "1g")
        decorated.putIfAbsent("taskmanager.memory.process.size", "1g")
        decorated.putIfAbsent("taskmanager.memory.managed.fraction", 0.2)
        decorated.putIfAbsent("parallelism.default", 1)
        decorated.putIfAbsent("taskmanager.numberOfTaskSlots", 1)
        decorated.putIfAbsent("kubernetes.rest-service.exposed.type", "ClusterIP")

        setKeyWarningIfAlreadyPresent(decorated, DeploymentOptions.TARGET.key(), KubernetesDeploymentTarget.APPLICATION.getName(), resource) // TODO expose in CRD
        setKeyWarningIfAlreadyPresent(decorated, KubernetesConfigOptions.NAMESPACE.key(), resource.metadata?.namespace!!, resource)
        setKeyWarningIfAlreadyPresent(decorated, KubernetesConfigOptions.CLUSTER_ID.key(), resource.metadata?.name!!, resource)

        setKeyWarningIfAlreadyPresent(decorated, "kubernetes.pod-template-file.jobmanager",
                "$confDir/${templateFileName(PodSpecType.JOB_MANAGER)}", resource)
        setKeyWarningIfAlreadyPresent(decorated, "kubernetes.pod-template-file.taskmanager",
                "$confDir/${templateFileName(PodSpecType.TASK_MANAGER)}", resource)

        setKeyWarningIfAlreadyPresent(decorated, DeploymentOptionsInternal.CONF_DIR.key(), confDir, resource)

        return decorated
    }

    private fun setKeyWarningIfAlreadyPresent(map: MutableMap<String, Any>, key: String, value: String, resource: HasMetadata) {
        if (map.containsKey(key)) {
            LOG.warn("Flink configuration for resource '{}' contained a key which will be overwritten: {}",
                    Cache.metaNamespaceKeyFunc(resource), key)
        }
        map[key] = value
    }

    @JvmStatic
    private fun templateFileName(type: PodSpecType): String {
        return "${type.name.lowercase()}_${FlorkConstants.POD_TEMPLATE_FILE_SUFFIX}"
    }

    private fun getFlinkPodTemplate(flinkJob: FlinkJobCustomResource, podSpec: PodSpec, desiredFlinkConfPath: String): Pod {
        if (flinkJob.spec.florkConf.shadowConfigFiles) {
            FlorkUtils.injectDesiredFlinkConfPathAsEnvVar(podSpec, desiredFlinkConfPath)
        }
        return Pod().apply {
            metadata = ObjectMeta().apply {
                name = flinkJob.metadata.name
                namespace = flinkJob.metadata.namespace
            }
            spec = podSpec
        }
    }

    @JvmStatic
    private fun processAdditionalConfFiles(jobKey: String, map: MutableMap<String, String>?, confPath: Path) {
        if (map != null) {
            if (map.remove(GlobalConfiguration.FLINK_CONF_FILENAME) != null) {
                LOG.warn("Job '{}' had {} in additionalConfFiles, which is not allowed. Ignoring.", jobKey, GlobalConfiguration.FLINK_CONF_FILENAME)
            }
            if (map.remove(FlorkConstants.POD_TEMPLATE_FILE_SUFFIX) != null) {
                LOG.warn("Job '{}' had {} in additionalConfFiles, which is not allowed. Ignoring.", jobKey, FlorkConstants.POD_TEMPLATE_FILE_SUFFIX)
            }

            for (entry in map) {
                LOG.trace("Writing additional conf file for '{}' named {}", jobKey, entry.key)
                PrintWriter(confPath.resolve(entry.key).toFile()).use { writer ->
                    writer.write(entry.value)
                }
            }
        }

        for (defaultConfFile in defaultLogConfFiles) {
            if (map == null || !map.containsKey(defaultConfFile.key)) {
                LOG.trace("Writing default log configuration file for '{}' named: {}", jobKey, defaultConfFile.key)
                PrintWriter(confPath.resolve(defaultConfFile.key).toFile()).use { writer ->
                    writer.write(defaultConfFile.value)
                }
            }
        }
    }

    suspend fun cleanFiles(confPath: Path) = withContext(NonCancellable) {
        runInterruptible {
            Files.walk(confPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete)
        }
    }
}
