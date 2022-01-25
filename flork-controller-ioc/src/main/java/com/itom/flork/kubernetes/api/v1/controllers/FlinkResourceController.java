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

package com.itom.flork.kubernetes.api.v1.controllers;

import com.itom.flork.kubernetes.api.v1.handlers.FlinkJobHandler;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

// https://itnext.io/customresource-improvements-in-fabric8-kubernetesclient-v5-0-0-4aef4d299323
@Named
@Singleton
public class FlinkResourceController extends AbstractFlinkResourceController {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkResourceController.class);

    @Inject
    public FlinkResourceController(Provider<KubernetesClient> kubernetesClientProvider) {
        super(kubernetesClientProvider.get());
        LOG.info("Instantiating FlinkResourceController controller.");

        String tenant = getTenant();
        long resyncPeriodSeconds = getResyncPeriod();
        String[] namespaces = getManagedNamespaces();

        try {
            for (String namespace : namespaces) {
                if (namespace.equals("*") && namespaces.length > 1) {
                    throw new IllegalStateException("Multiple namespaces are not allowed if any of them are the special wildcard '*'.");
                }
                informers.put(namespace, FlinkJobHandler.createInformerWithHandler(k8sClient, tenant, namespace, resyncPeriodSeconds));
            }
        } catch (Exception e) {
            this.close();
            throw e;
        }
    }
}
