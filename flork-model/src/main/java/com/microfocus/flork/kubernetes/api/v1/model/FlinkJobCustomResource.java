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

package com.microfocus.flork.kubernetes.api.v1.model;

import com.microfocus.flork.kubernetes.api.constants.FlinkJobConstants;
import com.microfocus.flork.kubernetes.api.constants.FlorkConstants;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.*;

@Group(FlorkConstants.CRD_GROUP)
@Version("v1")
@Kind(FlinkJobConstants.KIND)
@Plural(FlinkJobConstants.PLURAL)
@ShortNames({FlinkJobConstants.SHORT_NAME})
public class FlinkJobCustomResource extends CustomResource<FlinkJobSpec, FlinkJobStatus> implements Namespaced {
    private static final long serialVersionUID = 1L;

    @Override
    protected FlinkJobSpec initSpec() {
        return new FlinkJobSpec();
    }

    @Override
    protected FlinkJobStatus initStatus() {
        return new FlinkJobStatus();
    }
}
