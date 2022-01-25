/*
 * Copyright 2022 Micro Focus or one of its affiliates
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

package com.itom.flork.kubernetes.api.v1.reconcilers.phasers

import com.itom.flork.kubernetes.api.v1.model.FlinkJobCustomResource
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CoroutineFlinkJobReconcilerPhaserTest {
    companion object {
        private val KUBERNETES_SERVER = KubernetesServer(true, true)
        private val CACHE = Cache<FlinkJobCustomResource>()
        private val LISTER: AtomicReference<Lister<FlinkJobCustomResource>?> = AtomicReference(Lister(CACHE))

        private const val KEY = "flork/foo"

        @JvmStatic
        @BeforeAll
        fun setUp() {
            KUBERNETES_SERVER.before()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            KUBERNETES_SERVER.after()
        }
    }

    @BeforeEach
    fun purgeCache() {
        CACHE.remove(FlinkJobCustomResource().apply {
            metadata.apply {
                namespace = "flork"
                name = "foo"
            }
        })
    }

    @Test
    @Timeout(value = 10L, unit = TimeUnit.SECONDS)
    fun `basic start and cancel`() = runBlocking {
        KUBERNETES_SERVER.client.use { k8sClient ->
            val leaseDurationSeconds = 2L

            val phaserScope = CoroutineScope(SupervisorJob())
            val phaser = CoroutineFlinkJobReconcilerPhaser(phaserScope, k8sClient, LISTER, KEY, leaseDurationSeconds)
            Assertions.assertTrue(phaser.start(phaserScope, KEY))

            try {
                runInterruptible { phaser.callbacks.initialReadinessLatch.await() }
                Assertions.assertTrue(phaser.isActive())

                var lease = getLease(k8sClient, KEY)
                Assertions.assertNotNull(lease)

                phaser.cancel()
                lease = getLease(k8sClient, KEY)
                Assertions.assertNotNull(lease)

                delay((leaseDurationSeconds + 1L) * 1000L)

                lease = getLease(k8sClient, KEY)
                Assertions.assertNull(lease)

            } finally {
                phaser.cancel()
                phaserScope.cancel()
            }
        }
    }

    private suspend fun getLease(k8sClient: KubernetesClient, resourceKey: String): Lease? = withContext(Dispatchers.IO) {
        runInterruptible {
            k8sClient.leases()
                    .inNamespace(k8sClient.namespace)
                    .withName(AbstractReconcilerPhaser.getLeaseKey(resourceKey))
                    .get()
        }
    }
}
