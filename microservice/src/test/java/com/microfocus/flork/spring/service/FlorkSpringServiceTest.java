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

package com.microfocus.flork.spring.service;

import com.microfocus.flork.kubernetes.api.v1.model.FlinkJobCustomResource;
import com.microfocus.flork.mock.TestSpringConfig;
import com.microfocus.flork.spring.config.FlorkSpringConfig;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                FlorkSpringConfig.class, TestSpringConfig.class
        }
)
@EnableAutoConfiguration
@ActiveProfiles("crd")
@TestPropertySource(locations = "classpath:application-test.properties")
public class FlorkSpringServiceTest {
    private static final Logger LOG = LoggerFactory.getLogger(FlorkSpringServiceTest.class);

    @LocalServerPort
    private int serverPort;

    private WebTestClient webTestClient = null;

    @BeforeAll
    void setUpWebClient() throws SSLException {
        if (webTestClient != null) {
            return;
        }

        SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();

        HttpClient httpClient = HttpClient.create()
                .secure(t -> t.sslContext(sslContext));

        ClientHttpConnector clientHttpConnector = new ReactorClientHttpConnector(httpClient);

        webTestClient = WebTestClient.bindToServer(clientHttpConnector)
                .baseUrl("https://localhost:" + serverPort + "/flork")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Test
    void sanity() {
        LOG.info("Port: {}", serverPort);

        FlinkJobCustomResource flinkJobCustomResource = new FlinkJobCustomResource();

        AdmissionRequest admissionRequest = new AdmissionRequest();
        admissionRequest.setObject(flinkJobCustomResource);
        admissionRequest.setUid("foobar");

        AdmissionReview admissionReview = new AdmissionReview();
        admissionReview.setRequest(admissionRequest);

        webTestClient.post()
                .uri("/webhooks/admission/flinkjob")
                .body(BodyInserters.fromValue(admissionReview))
                .exchange()
                .expectStatus().isOk();
    }
}
