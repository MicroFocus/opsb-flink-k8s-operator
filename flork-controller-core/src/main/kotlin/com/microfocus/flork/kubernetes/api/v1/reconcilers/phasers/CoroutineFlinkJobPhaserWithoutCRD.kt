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

package com.microfocus.flork.kubernetes.api.v1.reconcilers.phasers

import com.microfocus.flork.kubernetes.api.v1.reconcilers.utils.ConfigMapFlinkResourceOperations
import com.microfocus.flork.kubernetes.api.v1.reconcilers.utils.FlinkResourceOperations
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.cache.Lister
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

class CoroutineFlinkJobPhaserWithoutCRD(
    coroutineScope: CoroutineScope,
    k8sClient: KubernetesClient,
    lister: AtomicReference<Lister<com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource>?>,
    jobKey: String
) : CoroutineFlinkJobReconcilerPhaser(coroutineScope, k8sClient, lister, jobKey) {
    override val crOperations: FlinkResourceOperations<com.microfocus.flork.kubernetes.api.v1.model.FlinkJobSpec, com.microfocus.flork.kubernetes.api.v1.model.FlinkJobStatus, com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource> by lazy {
        ConfigMapFlinkResourceOperations(super.k8sClient)
    }
}