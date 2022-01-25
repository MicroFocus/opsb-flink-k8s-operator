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

package com.itom.flork.kubernetes.api.v1.controllers;

import com.itom.flork.kubernetes.api.v1.handlers.ConfigMapFlinkJobHandler;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.swagger.annotations.*;
import kotlin.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Named
@Singleton
@Path("/no-crd/helm-hooks")
@Api("Endpoints that can be used by Helm hooks.") // needed annotation for swagger documentation to work
public class ConfigMapFlinkResourceController extends AbstractFlinkResourceController implements WithoutCRD {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigMapFlinkResourceController.class);

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ConfigMapFlinkJobHandler flinkJobHandler;

    @Inject
    public ConfigMapFlinkResourceController(Provider<KubernetesClient> kubernetesClientProvider) {
        super(kubernetesClientProvider.get());
        LOG.info("Instantiating ConfigMapFlinkResourceController controller.");

        long resyncPeriodSeconds = getResyncPeriod();

        try {
            Triple<ConfigMapFlinkJobHandler, SharedIndexInformer<ConfigMap>, SharedIndexInformer<ConfigMap>> informersWithHandler =
                    ConfigMapFlinkJobHandler.createInformersWithHandler(k8sClient, resyncPeriodSeconds);

            flinkJobHandler = informersWithHandler.getFirst();
            informers.put(k8sClient.getNamespace() + "-flink-job-outer", informersWithHandler.getSecond());
            informers.put(k8sClient.getNamespace() + "-flink-job-inner", informersWithHandler.getThird());
        } catch (Throwable t) {
            this.close();
            throw t;
        }
    }

    @PUT
    @Path("/pre-upgrade")
    @Consumes(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Pauses the CM-based reconcilers.", nickname = "pauseForUpgrade")
    @ApiResponses({
            @ApiResponse(code = HttpServletResponse.SC_CREATED, message = "Paused."),
            @ApiResponse(code = HttpServletResponse.SC_OK, message = "Was already paused.")
    })
    public Response pauseForUpgrade(@ApiParam(required = true) Long pauseDurationSeconds) {
        boolean flag = flinkJobHandler.getPaused().getAndSet(true);
        LOG.info("Pausing reconcilers for {}s for Helm upgrade. Previous pause flag: {}", pauseDurationSeconds, flag);
        if (flag) {
            return Response.ok().build();
        } else {
            executorService.submit(() -> {
                try {
                    Thread.sleep(pauseDurationSeconds * 1000L);
                    flinkJobHandler.getPaused().set(false);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            return Response.created(URI.create("/no-crd/helm-hooks/pre-upgrade")).build();
        }
    }

    @PUT
    @Path("/post-upgrade")
    @ApiOperation(value = "Pauses the CM-based reconcilers indefinitely after a successful upgrade but before new containers are running.", nickname = "pauseAfterUpgrade")
    @ApiResponses({
            @ApiResponse(code = HttpServletResponse.SC_CREATED, message = "Paused."),
            @ApiResponse(code = HttpServletResponse.SC_NO_CONTENT, message = "Pause not needed.")
    })
    public Response pauseAfterUpgrade() {
        if (!flinkJobHandler.getPaused().get()) {
            LOG.info("Post-upgrade pause not needed.");
            return Response.noContent().build();
        }

        LOG.info("Pausing indefinitely.");
        onClose();
        return Response.created(URI.create("/no-crd/helm-hooks/post-upgrade")).build();
    }

    @Override
    protected void onClose() {
        executorService.shutdownNow();
        try {
            LOG.debug("Clean shutdown? {}", executorService.awaitTermination(2L, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
