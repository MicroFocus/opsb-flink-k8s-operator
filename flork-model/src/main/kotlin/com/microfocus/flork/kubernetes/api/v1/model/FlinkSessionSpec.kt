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

package com.microfocus.flork.kubernetes.api.v1.model

import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.PodSpec
import java.io.Serializable

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FlinkSessionSpec(
    var florkConf: FlorkConf = FlorkConf(),
    var flinkConf: MutableMap<String, Any> = mutableMapOf(),
    var jobManagerPodSpec: PodSpec = PodSpec(),
    var taskManagerPodSpec: PodSpec? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
