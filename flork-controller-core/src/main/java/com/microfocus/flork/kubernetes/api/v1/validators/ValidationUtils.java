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

package com.microfocus.flork.kubernetes.api.v1.validators;

import com.microfocus.flork.kubernetes.api.constants.FlorkConstants;
import com.microfocus.flork.kubernetes.api.utils.FlinkConfUtils;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponseBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.Map;

public class ValidationUtils {
    private static final Logger LOG = LoggerFactory.getLogger(ValidationUtils.class);

    public static boolean isConfValidForFlork(Map<String, Object> map) {
        return nullableMapContainsKey(map, FlinkConfUtils.FLINK_CONF_DIR_KEY) &&
                map.get(FlinkConfUtils.FLINK_CONF_DIR_KEY).equals(FlorkConstants.FLORK_CONF_DIR);
    }

    public static boolean nullableMapContainsKey(Map<String, ?> map, String key) {
        return map != null && map.containsKey(key);
    }

    public static AdmissionResponse getRejectingResponse(AdmissionReview admissionReview, HasMetadata customResource, String message) {
        LOG.info("Rejecting {} [{}]: {}", customResource.getKind(), Cache.metaNamespaceKeyFunc(customResource), message);
        Status status = new Status();
        status.setCode(HttpURLConnection.HTTP_BAD_REQUEST);
        status.setMessage(message);
        return new AdmissionResponseBuilder()
                .withUid(admissionReview.getRequest().getUid())
                .withAllowed(false)
                .withStatus(status)
                .build();
    }
}
