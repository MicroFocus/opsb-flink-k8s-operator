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

package com.microfocus.jsonschema.model

import com.fasterxml.jackson.annotation.*

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(value = ["description"], allowSetters = true)
data class SimplifiedJsonSchemaProperty(
        @get:JsonIgnore
        @set:JsonProperty("\$ref")
        var ref: String? = null,
        var items: SimplifiedJsonSchemaProperty? = null,
        var additionalProperties: SimplifiedJsonSchemaProperty? = null,
        var description: String? = null,
        var properties: MutableMap<String, SimplifiedJsonSchemaProperty> = mutableMapOf()
) {
    private val anyProperties = mutableMapOf<String, Any?>()

    @JsonAnyGetter
    fun getAnyProperties(): MutableMap<String, Any?> = anyProperties

    @JsonAnySetter
    fun setAnyProperties(key: String, value: Any?) {
        if (value != null && Utils.isValidAnyPropertyKey(key)) {
            anyProperties[key] = value
        }
    }
}
