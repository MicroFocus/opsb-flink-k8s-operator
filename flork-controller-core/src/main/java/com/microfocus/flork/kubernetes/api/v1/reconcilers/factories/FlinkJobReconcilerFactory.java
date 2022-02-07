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

package com.microfocus.flork.kubernetes.api.v1.reconcilers.factories;

import com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource;
import com.microfocus.flork.kubernetes.api.v1.reconcilers.FlinkJobReconciler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.cache.Lister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public abstract class FlinkJobReconcilerFactory {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkJobReconcilerFactory.class);

    private static final List<FlinkJobReconcilerFactory> INSTANCES = new ArrayList<>();

    private synchronized static void registerInstance(FlinkJobReconcilerFactory factory) {
        INSTANCES.add(factory);
    }

    protected FlinkJobReconcilerFactory() {
        registerInstance(this);
    }

    public synchronized static void stopFactories() {
        try {
            for (FlinkJobReconcilerFactory factory : INSTANCES) {
                try {
                    factory.stopAll();
                } catch (Exception e) {
                    LOG.error("Could not stop {}:", factory, e);
                }
            }
        } finally {
            INSTANCES.clear();
        }
    }

    public abstract FlinkJobReconciler create(KubernetesClient k8sClient, AtomicReference<Lister<FlinkJobCustomResource>> lister);

    public abstract void stopAll();
}
