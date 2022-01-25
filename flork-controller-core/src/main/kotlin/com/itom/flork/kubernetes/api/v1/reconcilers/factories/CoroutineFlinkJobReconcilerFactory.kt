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

package com.itom.flork.kubernetes.api.v1.reconcilers.factories

import com.itom.flork.kubernetes.api.v1.model.FlinkJobCustomResource
import com.itom.flork.kubernetes.api.v1.reconcilers.CoroutineFlinkJobReconciler
import com.itom.flork.kubernetes.api.v1.reconcilers.FlinkJobReconciler
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.cache.Lister
import java.util.concurrent.atomic.AtomicReference

class CoroutineFlinkJobReconcilerFactory : FlinkJobReconcilerFactory() {
    override fun create(k8sClient: KubernetesClient, lister: AtomicReference<Lister<FlinkJobCustomResource>?>): FlinkJobReconciler {
        return CoroutineFlinkJobReconciler(k8sClient, lister, true)
    }

    override fun stopAll() {
        CoroutineFlinkJobReconciler.resetScope()
    }
}
