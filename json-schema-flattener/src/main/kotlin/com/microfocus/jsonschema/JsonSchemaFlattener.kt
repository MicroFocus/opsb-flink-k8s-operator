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

package com.microfocus.jsonschema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.microfocus.jsonschema.model.SimplifiedJsonSchemaDefinition
import com.microfocus.jsonschema.model.SimplifiedJsonSchemaProperty
import com.microfocus.jsonschema.model.SwaggerSubset
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.exitProcess

// https://stackoverflow.com/a/57346163/5793905
@CommandLine.Command(name = "json-schema-flattener")
class JsonSchemaFlattener : Callable<Int> {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(JsonSchemaFlattener::class.java)

        private val mapper: ObjectMapper

        init {
            val yamlFactory = YAMLFactory().apply {
                disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            }
            mapper = ObjectMapper(yamlFactory).apply {
                disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        @JvmStatic
        fun main(vararg args: String) {
            exitProcess(CommandLine(JsonSchemaFlattener()).execute(*args))
        }
    }

    private val propertyKeysToPrune = mutableSetOf<String>()

    @CommandLine.Option(names = ["-f", "--file"], required = true, paramLabel = "<SWAGGER_API>", description = ["File with the Swagger API definition in JSON format."])
    lateinit var input: File

    @CommandLine.Option(names = ["-r", "--root"], required = true, paramLabel = "<DEFINITION_KEY>", description = ["Where to start flattening; a key from the Swagger definitions."])
    lateinit var rootKey: String

    @CommandLine.Option(names = ["-o", "--output"], paramLabel = "<PATH>", description = ["Path of desired output YAML."], showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    var outputYaml = File("flattened_schema.yaml")

    @CommandLine.Option(names = ["-p", "--prune"], paramLabel = "<LIST>", description = ["Comma-separated list of properties that should be removed."])
    @Suppress("unused")
    fun setPropertiesToPrune(list: String) {
        list.split(",").forEach {
            propertyKeysToPrune.add(it.trim())
        }
    }

    override fun call(): Int = try {
        LOG.debug("Properties to prune: {}", propertyKeysToPrune)
        val json = mapper.readValue(input, SwaggerSubset::class.java)
        val root = json.definitions[rootKey] ?: throw IllegalArgumentException("Key doesn't exist: $rootKey")
        populateReferencesRecursively(root, json.definitions)
        mapper.writeValue(outputYaml, root)
        0
    } catch (e: Throwable) {
        LOG.error("Error during flattening:", e)
        if (e is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        1
    }

    private fun populateReferencesRecursively(node: SimplifiedJsonSchemaDefinition, definitions: MutableMap<String, SimplifiedJsonSchemaDefinition>) {
        val iter = node.properties.iterator()

        while (iter.hasNext()) {
            val entry = iter.next()
            if (propertyKeysToPrune.contains(entry.key)) {
                iter.remove()
                continue
            }

            val property = entry.value
            when {
                property.ref != null -> {
                    recurseOne(property, definitions)
                }
                property.items?.ref != null -> {
                    recurseOne(property.items!!, definitions)
                }
                property.additionalProperties?.ref != null -> {
                    recurseOne(property.additionalProperties!!, definitions)
                }
                property.additionalProperties?.items != null -> {
                    recurseOne(property.additionalProperties!!.items!!, definitions)
                }
            }
        }
    }

    private fun recurseOne(property: SimplifiedJsonSchemaProperty, definitions: MutableMap<String, SimplifiedJsonSchemaDefinition>) {
        val key = property.ref?.substringAfterLast("/") ?: throw ConcurrentModificationException("Ref wasn't null but now it is.")
        val def = definitions[key] ?: throw IllegalStateException("Could not find key while recursing: $key")

        property.properties = def.properties
        for (entry in def.getAnyProperties()) {
            if (!property.getAnyProperties().containsKey(entry.key)) {
                property.setAnyProperties(entry.key, entry.value)
            }
        }

        property.ref = null
        populateReferencesRecursively(def, definitions)
    }
}
