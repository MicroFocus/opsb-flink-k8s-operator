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

import com.itom.flork.kubernetes.api.v1.reconcilers.factories.FlinkJobReconcilerFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class AbstractFlinkResourceController {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractFlinkResourceController.class);

    protected final KubernetesClient k8sClient;
    protected final Map<String, SharedIndexInformer<?>> informers = new HashMap<>();

    public AbstractFlinkResourceController(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    protected String getTenant() {
        if (System.getenv("TENANT_ID") == null) {
            return "default";
        } else {
            return System.getenv("TENANT_ID");
        }
    }

    protected long getResyncPeriod() {
        try {
            return Long.parseLong(System.getenv("INFORMER_RESYNC_PERIOD_SECONDS"));
        } catch (NumberFormatException e) {
            LOG.debug("Environment variable INFORMER_RESYNC_PERIOD_SECONDS is not a valid long: {}", e.getMessage());
            return 30L; // I think this must be greater than leader election's lease's duration.
        }
    }

    @NotNull
    protected String[] getManagedNamespaces() {
        if (System.getenv("MANAGED_NAMESPACES") == null) {
            throw new IllegalStateException("Environment variable MANAGED_NAMESPACES cannot be null.");
        } else {
            return System.getenv("MANAGED_NAMESPACES").split(",");
        }
    }

    @PostConstruct
    public synchronized void checkThatAllInformersAreRunning() throws InterruptedException {
        try {
            boolean anyNotSyncedYet;
            do {
                anyNotSyncedYet = false;
                for (Map.Entry<String, SharedIndexInformer<?>> entry : informers.entrySet()) {
                    if (!entry.getValue().hasSynced()) {
                        LOG.debug("Flink job informer for namespace {} has not synced yet.", entry.getKey());
                        anyNotSyncedYet = true;
                        Thread.sleep(100L);
                        break;
                    }
                }
            } while (anyNotSyncedYet);

        } catch (Exception e) {
            this.close();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }

        LOG.info("Controller ready.");
    }

    @PreDestroy
    public synchronized void close() {
        for (Map.Entry<String, SharedIndexInformer<?>> entry : informers.entrySet()) {
            LOG.info("Stopping informer with key {}.", entry.getKey());
            entry.getValue().close();
        }
        try {
            FlinkJobReconcilerFactory.stopFactories();
            onClose();
        } finally {
            k8sClient.close();
        }
    }

    protected void onClose() {
        // nop by default
    }
}
