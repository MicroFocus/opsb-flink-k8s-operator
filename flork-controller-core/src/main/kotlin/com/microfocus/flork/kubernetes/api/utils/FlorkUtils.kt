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

package com.microfocus.flork.kubernetes.api.utils

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.PodSpec
import org.apache.flink.client.deployment.ClusterClientFactory
import org.apache.flink.client.deployment.ClusterDescriptor
import org.apache.flink.configuration.Configuration
import org.apache.flink.configuration.SecurityOptions
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions
import org.apache.flink.kubernetes.utils.Constants
import org.slf4j.LoggerFactory

object FlorkUtils {
    private val LOG = LoggerFactory.getLogger(FlorkUtils::class.java)

    private const val ENV_VAR_KEY = "FLORK_DESIRED_FLINK_CONF_PATH"

    @JvmStatic
    fun injectDesiredFlinkConfPathAsEnvVar(podSpec: PodSpec, path: String) {
        var mainContainer = podSpec.containers.find { it.name == Constants.MAIN_CONTAINER_NAME }
        if (mainContainer == null) {
            mainContainer = Container()
            podSpec.containers.add(mainContainer)
        }

        mainContainer.env.removeIf { it.name == ENV_VAR_KEY }
        mainContainer.env.add(EnvVar(ENV_VAR_KEY, path, null))
    }

    @JvmStatic
    fun getDescriptorWithTlsIfNeeded(jobKey: String, factory: ClusterClientFactory<String>, baseConfig: Configuration, preferInternalService: Boolean): ClusterDescriptor<String> {
        if (preferInternalService) {
            // at this point the deployment is done, so this will only affect the rest client
            baseConfig.set(KubernetesConfigOptions.REST_SERVICE_EXPOSED_TYPE, KubernetesConfigOptions.ServiceExposedType.ClusterIP)
        }

        if (!SecurityOptions.isRestSSLEnabled(baseConfig)) {
            LOG.debug("Job '{}' does not have TLS enabled for REST communication.", jobKey)
            return factory.createClusterDescriptor(baseConfig)
        }

        LOG.debug("Using TLS for REST communication with '{}'.", jobKey)
        if (SecurityOptions.isRestSSLAuthenticationEnabled(baseConfig)) {
            LOG.debug("Trying to also use client certificates for REST communication with '{}'.", jobKey)
        }

        baseConfig.set(SecurityOptions.SSL_ALGORITHMS, com.microfocus.flork.kubernetes.api.constants.RuntimeConstants.SSL_ALGORITHMS)
        // trust store settings must not be null at this point
        baseConfig.set(SecurityOptions.SSL_REST_TRUSTSTORE, System.getenv("CLIENT_TRUSTSTORE"))
        baseConfig.set(SecurityOptions.SSL_REST_TRUSTSTORE_PASSWORD, System.getenv("TRUSTSTORE_PASSWORD"))

        System.getenv("CLIENT_KEYSTORE")?.let { ks ->
            baseConfig.set(SecurityOptions.SSL_REST_KEYSTORE, ks)
        }
        System.getenv("KEYSTORE_PASSWORD")?.let { kspw ->
            baseConfig.set(SecurityOptions.SSL_REST_KEYSTORE_PASSWORD, kspw)
            baseConfig.set(SecurityOptions.SSL_REST_KEY_PASSWORD, kspw)
        }

        return factory.createClusterDescriptor(baseConfig)
    }
}
