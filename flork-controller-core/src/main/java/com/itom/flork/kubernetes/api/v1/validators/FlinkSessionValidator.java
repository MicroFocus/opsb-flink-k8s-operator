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

package com.itom.flork.kubernetes.api.v1.validators;

import com.itom.flork.kubernetes.api.constants.FlorkConstants;
import com.itom.flork.kubernetes.api.utils.FlinkConfUtils;
import com.itom.flork.kubernetes.api.v1.model.FlinkSessionCustomResource;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponseBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class FlinkSessionValidator {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkSessionValidator.class);

    public AdmissionReview review(AdmissionReview admissionReview) {
        KubernetesResource requestObject = admissionReview.getRequest().getObject();
        LOG.debug("Got review with request object: {}", requestObject);

        FlinkSessionCustomResource flinkSession = (FlinkSessionCustomResource) requestObject;

        Map<String, Object> flinkConf = flinkSession.getSpec().getFlinkConf();

        AdmissionResponse admissionResponse;

        if (ValidationUtils.isConfValidForFlork(flinkConf)) {
            admissionResponse = ValidationUtils.getRejectingResponse(admissionReview, flinkSession,
                    String.format("Directory [%s] cannot be used as key [%s]", FlorkConstants.FLORK_CONF_DIR, FlinkConfUtils.FLINK_CONF_DIR_KEY));
        } else {
            admissionResponse = new AdmissionResponseBuilder()
                    .withUid(admissionReview.getRequest().getUid())
                    .withAllowed(true)
                    .build();
        }

        return new AdmissionReview(
                admissionReview.getApiVersion(),
                admissionReview.getKind(),
                null,
                admissionResponse
        );
    }
}
