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

package com.itom.flork.kubernetes.api.hooks;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class UpgradeConfigMapHook {
    private static final Logger LOG = LoggerFactory.getLogger(UpgradeConfigMapHook.class);

    private static final String PRE_PHASE = "pre";
    private static final String POST_PHASE = "post";

    /**
     * Expected arguments:
     * <ol>
     *     <li>Either "pre" or "post" indicating upgrade phase.</li>
     *     <li>Name of the Kubernetes service for the controller.</li>
     *     <li>Optional - a REST path prefix.</li>
     *     <li>Optional - the name of the service port that should be used.</li>
     * </ol>
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0 || (!args[0].equalsIgnoreCase(PRE_PHASE) && !args[0].equalsIgnoreCase(POST_PHASE))) {
            LOG.error("Must specify {} or {} as first argument.", PRE_PHASE, POST_PHASE);
            System.exit(1);
        } else if (args.length < 2) {
            LOG.error("Must specify controller's service name as second argument.");
            System.exit(1);
        }

        final String phase = args[0];
        final String svc = args[1];

        final KubernetesClient kubernetesClient = new DefaultKubernetesClient();
        final Service service = kubernetesClient.services()
                .inNamespace(kubernetesClient.getNamespace())
                .withName(svc)
                .get();

        validateService(kubernetesClient, service, svc);

        try {
            final IntOrString targetPort = getTargetPort(args, service);

            final PodList pods = kubernetesClient.pods()
                    .inNamespace(kubernetesClient.getNamespace())
                    .withLabels(service.getSpec().getSelector())
                    .list();

            LOG.info("Found {} pod(s) with endpoints for service {}.", pods.getItems().size(), svc);
            if (pods.getItems().isEmpty()) {
                LOG.warn("No pods are currently active.");
                return;
            }

            long numReplicaSets = pods.getItems().stream()
                    .map(pod -> pod.getMetadata().getGenerateName())
                    .distinct()
                    .count();

            if (phase.equalsIgnoreCase(POST_PHASE) && numReplicaSets < 2L) {
                LOG.info("Exiting early because only {} replica set(s) were found.", numReplicaSets);
                return;
            }

            final List<String> ips = pods.getItems().stream()
                    .map(pod -> pod.getStatus().getPodIP())
                    .collect(Collectors.toList());

            if (ips.isEmpty()) {
                LOG.warn("Could not determine IPs of the pods.");
                return;
            }

            validateTargetPort(targetPort, pods);
            LOG.info("Using target port {}", targetPort);

            String restPathPrefix = "";
            if (args.length >= 3) {
                restPathPrefix = args[2];
                LOG.info("Using REST path prefix: {}", restPathPrefix);
            }

            try (final CloseableHttpClient httpClient = getHttpClient()) {
                sendPauseRequest(ips, targetPort, phase, httpClient, restPathPrefix);
                LOG.info("Hook completed.");
            }
        } finally {
            kubernetesClient.close();
        }
    }

    private static void validateService(KubernetesClient kubernetesClient, Service service, String serviceName) {
        if (service == null) {
            LOG.error("Could not find service {} in Kubernetes.", serviceName);
            kubernetesClient.close();
            System.exit(1);
        } else if (service.getSpec().getPorts().isEmpty()) {
            LOG.error("Service {} does not specify any ports.", serviceName);
            kubernetesClient.close();
            System.exit(1);
        }
    }

    private static IntOrString getTargetPort(String[] args, Service service) {
        if (args.length < 4) {
            return service.getSpec().getPorts().get(0).getTargetPort();
        } else {
            final String portName = args[3];
            return service.getSpec().getPorts().stream()
                    .filter(port -> port.getName().equalsIgnoreCase(portName))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Could not find service port named " + portName))
                    .getTargetPort();
        }
    }

    private static void validateTargetPort(IntOrString targetPort, PodList pods) {
        if (targetPort.getIntVal() == null) {
            pods.getItems().stream()
                    .flatMap(pod -> pod.getSpec().getContainers().stream())
                    .flatMap(cnt -> cnt.getPorts().stream())
                    .filter(port -> port.getName().equalsIgnoreCase(targetPort.getStrVal()))
                    .findFirst()
                    .ifPresent(port -> targetPort.setIntVal(port.getContainerPort()));

            if (targetPort.getIntVal() == null) {
                throw new IllegalArgumentException("Could not find port corresponding to name " + targetPort.getStrVal());
            }
        }
    }

    private static CloseableHttpClient getHttpClient() {
        return HttpClients.custom()
                .setSSLContext(SSLContexts.createSystemDefault())
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();
    }

    private static void sendPauseRequest(List<String> ips, IntOrString targetPort, String phase, CloseableHttpClient httpClient, String restPathPrefix) throws IOException {
        for (String ip : ips) {
            String url = String.format("https://%s:%d%s/no-crd/helm-hooks/%s-upgrade",
                    ip, targetPort.getIntVal(), restPathPrefix, phase);

            HttpPut put = new HttpPut(url);
            if (phase.equalsIgnoreCase(PRE_PHASE)) {
                String duration = System.getenv("PAUSE_DURATION_SECONDS") == null ? "600" : System.getenv("PAUSE_DURATION_SECONDS");
                StringEntity entity = new StringEntity(duration);
                put.setEntity(entity);
            }

            put.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.TEXT_PLAIN.getMimeType());

            try {
                CloseableHttpResponse response = httpClient.execute(put);
                if (response.getStatusLine().getStatusCode() >= 300) {
                    LOG.error("Unexpected status code after {}-upgrade request at {}: {}",
                            phase, ip, response.getStatusLine().getStatusCode());
                }
                EntityUtils.consume(response.getEntity());
            } catch (Exception e) {
                LOG.warn("Potentially ignorable exception while trying to PUT pause request at {}:", ip, e);
            }
        }
    }
}
