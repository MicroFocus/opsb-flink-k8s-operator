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

package com.itom.flork.kubernetes.api.v1.controllers.webhooks;

import com.itom.flork.kubernetes.api.v1.validators.FlinkJobValidator;
import com.itom.flork.kubernetes.api.v1.validators.FlinkSessionValidator;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.swagger.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Named
@Singleton
@Path("/webhooks/admission")
@Api("Flork's validating admission webhooks.") // needed annotation for swagger documentation to work
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ValidatingWebhooksController {
    private static final Logger LOG = LoggerFactory.getLogger(ValidatingWebhooksController.class);

    private final FlinkJobValidator flinkJobValidator = new FlinkJobValidator();
    private final FlinkSessionValidator flinkSessionValidator = new FlinkSessionValidator();

    public ValidatingWebhooksController() {
        LOG.debug("Instantiating controller for /webhook/admissions");
    }

    @POST
    @Path("/flinkjob")
    @ApiOperation(value = "Validates a FlinkJob CR.", nickname = "reviewFlinkJobRequest")
    @ApiResponses({
            @ApiResponse(code = HttpServletResponse.SC_OK, message = "Resource associated with the request is allowed.", response = AdmissionReview.class),
            @ApiResponse(code = HttpServletResponse.SC_BAD_REQUEST, message = "Something in the resource is not allowed."),
            @ApiResponse(code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message = "Internal Server Error.")
    })
    public Response reviewFlinkJobRequest(@ApiParam AdmissionReview admissionReview) {
        LOG.trace("POST at /flinkjob endpoint.");
        return Response.ok()
                .entity(flinkJobValidator.review(admissionReview))
                .build();
    }

    @POST
    @Path("/flinksession")
    @ApiOperation(value = "Validates a FlinkSession CR.", nickname = "reviewFlinkSessionRequest")
    @ApiResponses({
            @ApiResponse(code = HttpServletResponse.SC_OK, message = "Resource associated with the request is allowed.", response = AdmissionReview.class),
            @ApiResponse(code = HttpServletResponse.SC_BAD_REQUEST, message = "Something in the resource is not allowed."),
            @ApiResponse(code = HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message = "Internal Server Error.")
    })
    public Response reviewFlinkSessionRequest(@ApiParam AdmissionReview admissionReview) {
        LOG.trace("POST at /flinksession endpoint.");
        return Response.ok()
                .entity(flinkSessionValidator.review(admissionReview))
                .build();
    }
}
