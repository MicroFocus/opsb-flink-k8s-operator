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

package com.itom.flork.kubernetes.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpec;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class FlinkJobSpec implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty
    public FlorkConf florkConf = new FlorkConf();

    @JsonProperty
    public Map<String, Object> flinkConf;

    @JsonProperty
    public String jobClassName;

    @JsonProperty
    public String[] jobArgs;

    @JsonProperty
    public FlinkJobPolicies policies;

    @JsonProperty
    public ObjectMeta jobManagerPodMeta;

    @JsonProperty
    public PodSpec jobManagerPodSpec;

    @JsonProperty
    public PodSpec taskManagerPodSpec;

    @JsonProperty
    public Map<String, String> additionalConfFiles;

    @Override
    public String toString() {
        return "FlinkJobSpec{" +
                "florkConf=" + florkConf +
                ", flinkConf=" + flinkConf +
                ", jobClassName=" + jobClassName +
                ", jobArgs=" + Arrays.toString(jobArgs) +
                ", policies=" + policies +
                ", jobManagerPodMeta=" + jobManagerPodMeta +
                ", jobManagerPodSpec=" + jobManagerPodSpec +
                ", taskManagerPodSpec=" + taskManagerPodSpec +
                ", additionalConfFiles=" + (additionalConfFiles == null ? "[]" : additionalConfFiles.keySet()) +
                '}';
    }
}
