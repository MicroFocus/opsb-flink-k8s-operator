#!/usr/bin/env bash
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

. /opt/flork/bin/tls-stores-setup.sh

exec java -Xms500m -Xmx500m \
  -Djavax.net.ssl.keyStoreType=PKCS12 -Djavax.net.ssl.trustStoreType=PKCS12 \
  -cp '/opt/flork/app/lib/*' com.itom.flork.spring.FlorkSpringMain
