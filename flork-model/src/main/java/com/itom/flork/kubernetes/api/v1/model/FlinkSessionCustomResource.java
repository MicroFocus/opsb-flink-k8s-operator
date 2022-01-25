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

package com.itom.flork.kubernetes.api.v1.model;

import com.itom.flork.kubernetes.api.constants.FlinkSessionConstants;
import com.itom.flork.kubernetes.api.constants.FlorkConstants;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.*;

@Group(FlorkConstants.CRD_GROUP)
@Version("v1")
@Kind(FlinkSessionConstants.KIND)
@Plural(FlinkSessionConstants.PLURAL)
@ShortNames({FlinkSessionConstants.SHORT_NAME})
public class FlinkSessionCustomResource extends CustomResource<FlinkSessionSpec, FlinkSessionStatus> implements Namespaced {
    private static final long serialVersionUID = 1L;

    @Override
    protected FlinkSessionSpec initSpec() {
        return new FlinkSessionSpec();
    }

    @Override
    protected FlinkSessionStatus initStatus() {
        return new FlinkSessionStatus();
    }
}
