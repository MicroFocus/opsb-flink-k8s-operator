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

package com.microfocus.flork.kubernetes.api.v1.reconcilers.utils

import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FlinkResourceDeploymentMonitor(private val namespace: String, private val name: String) : ResourceEventHandler<Deployment> {
    companion object {
        private val LOG = LoggerFactory.getLogger(FlinkResourceDeploymentMonitor::class.java)
    }

    val addedFlag = AtomicBoolean(false)
    val deletionLatch = AtomicReference(CountDownLatch(1))

    override fun onAdd(obj: Deployment?) {
        if (addedFlag.compareAndSet(false, true)) {
            LOG.info("Flink resource deployment added: {}/{}", namespace, name)
        }
    }

    override fun onUpdate(oldObj: Deployment?, newObj: Deployment?) {
        LOG.debug("Flink resource deployment updated: {}/{}", namespace, name)
    }

    override fun onDelete(obj: Deployment?, deletedFinalStateUnknown: Boolean) {
        if (addedFlag.compareAndSet(true, false)) {
            LOG.info("Flink resource deployment deleted: {}/{}", namespace, name)
            deletionLatch.get().countDown()
        }
    }
}
