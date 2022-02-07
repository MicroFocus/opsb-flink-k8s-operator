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

package com.microfocus.flork.kubernetes.api.v1.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class FlorkConf implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonIgnore
    private final Map<String, Object> additionalFields = new HashMap<>();

    public boolean shadowConfigFiles = false;
    public boolean preferClusterInternalService = true;
    public boolean cleanHighAvailability = true;

    @JsonAnyGetter
    public Map<String, Object> getAdditionalFields() {
        return additionalFields;
    }

    @JsonAnySetter
    public FlorkConf setAdditionalProperty(String key, Object value) {
        additionalFields.put(key, value);
        return this;
    }

    @Override
    public String toString() {
        return "FlorkConf{" +
                "shadowConfigFiles=" + shadowConfigFiles +
                ", preferClusterInternalService=" + preferClusterInternalService +
                ", cleanHighAvailability=" + cleanHighAvailability +
                '}';
    }
}
