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

package com.itom.flork.kubernetes.api.utils;

import org.apache.flink.client.deployment.*;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.configuration.Configuration;
import org.jetbrains.annotations.Nullable;

public class FlinkApplicationClusterDeployer {
    public static ClusterClientFactory<String> getClusterClientFactory(Configuration flinkConfig) {
        ClusterClientServiceLoader clusterClientServiceLoader = new DefaultClusterClientServiceLoader();
        return clusterClientServiceLoader.getClusterClientFactory(flinkConfig);
    }

    public static void deployWithConfigFile(Configuration flinkConfig, @Nullable String jobClassName, String[] jobArgs) throws Exception {
        ClusterClientFactory<String> factory = getClusterClientFactory(flinkConfig);

        ClusterSpecification clusterSpecification = factory.getClusterSpecification(flinkConfig);
        ApplicationConfiguration applicationConfig = new ApplicationConfiguration(jobArgs, jobClassName);

        try (ClusterDescriptor<String> descriptor = factory.createClusterDescriptor(flinkConfig)) {
            descriptor.deployApplicationCluster(clusterSpecification, applicationConfig);
        }
    }
}
