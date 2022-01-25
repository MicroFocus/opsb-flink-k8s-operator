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

package com.itom.flork.kubernetes.api.constants;

public class FlorkConstants {
    public static final String CRD_GROUP = "flork.itom.com";

    public static final String FLORK_CONF_DIR = "/opt/flork/conf";

    // for Flink jobs
    public static final String FLORK_FJ_CM_LABEL = CRD_GROUP + "/flink-job";

    // for Flink job's status config map
    public static final String FLORK_FJ_SCM_LABEL = CRD_GROUP + "/flink-job-status";

    // for Flink sessions
    public static final String FLORK_FS_CM_LABEL = CRD_GROUP + "/flink-session";

    public static final String METADATA_CM_VALIDITY_LABEL = "validity." + CRD_GROUP;

    public static final String POD_TEMPLATE_FILE_SUFFIX = "pod_template.yaml";
}
