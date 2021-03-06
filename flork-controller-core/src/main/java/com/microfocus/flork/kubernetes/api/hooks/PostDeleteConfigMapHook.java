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

package com.microfocus.flork.kubernetes.api.hooks;

import com.microfocus.flork.kubernetes.api.constants.FlorkConstants;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostDeleteConfigMapHook {
    private static final Logger LOG = LoggerFactory.getLogger(PostDeleteConfigMapHook.class);

    public static void main(String[] args) {
        LOG.info("Trying to clean up status config maps.");

        try (KubernetesClient kubernetesClient = new DefaultKubernetesClient()) {
            kubernetesClient.configMaps()
                    .inNamespace(kubernetesClient.getNamespace())
                    .withLabel(FlorkConstants.FLORK_FJ_SCM_LABEL)
                    .delete();
        }
    }
}
