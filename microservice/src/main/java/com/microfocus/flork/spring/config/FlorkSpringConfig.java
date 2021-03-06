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

package com.microfocus.flork.spring.config;

import com.microfocus.flork.kubernetes.api.v1.controllers.WithoutCRD;
import com.microfocus.flork.kubernetes.api.v1.controllers.webhooks.ValidatingWebhooksController;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@Profile("crd")
@Configuration
@EnableWebSecurity
@PropertySource("classpath:application.properties")
@ComponentScan(
        basePackages = {
                "com.microfocus.flork"
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {
                        Configuration.class
                }),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                        WithoutCRD.class
                })
        }
)
@Import({WebSecurityConfig.class, KubernetesClientProvider.class})
public class FlorkSpringConfig extends ResourceConfig {
        public FlorkSpringConfig() {
                this.register(ValidatingWebhooksController.class);
        }
}
