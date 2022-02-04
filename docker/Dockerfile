#
# Copyright 2021-2022 Micro Focus or one of its affiliates
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

FROM openjdk:17.0.1-jdk-bullseye

ARG BUILD_VERSION=latest
ARG COMMIT_ID=HEAD

LABEL "maintainer"="Micro Focus"
LABEL "com.microfocus.image-name"="itom-flork"
LABEL "com.microfocus.image-version"="$BUILD_VERSION"
LABEL "com.microfocus.image-commit-id"="$COMMIT_ID"
LABEL "com.microfocus.itom.flork"="$BUILD_VERSION"

COPY flork /opt/flork/

ENTRYPOINT /opt/flork/bin/container-entrypoint.sh
