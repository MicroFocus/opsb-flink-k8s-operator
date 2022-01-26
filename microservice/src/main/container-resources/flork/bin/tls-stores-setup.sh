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

password=$(openssl rand -hex 32)

export SERVER_KEYSTORE=/tmp/keystore.p12
export KEYSTORE_PASSWORD="$password"
export SERVER_KEY_ALIAS=flork

export SERVER_TRUSTSTORE=/tmp/truststore.p12
export TRUSTSTORE_PASSWORD="$password"

if ! openssl pkcs12 -export -in /opt/flork/tls/server.crt -inkey /opt/flork/tls/server.key -name "$SERVER_KEY_ALIAS" -out "$SERVER_KEYSTORE" -passout "pass:$password"; then
  echo "Could not create key store."
  exit 1
fi

if [ -e /var/run/secrets/kubernetes.io/serviceaccount/ca.crt ]; then
  echo "Trying to add kubernetes certificate to trust store."
  if ! keytool -importcert -storetype PKCS12 -alias kubernetes -file /var/run/secrets/kubernetes.io/serviceaccount/ca.crt -keystore "$SERVER_TRUSTSTORE" -storepass "$password" -noprompt -trustcacerts; then
    echo "Could not import kubernetes certificate into trust store."
    exit 1
  fi
fi

shopt -s nullglob
for cert in /opt/flork/tls/trustedCAs/*; do
  if [ ! -d "$cert" ]; then
    certBaseName=$(basename "$cert")
    echo "Trying to add $certBaseName to trust store."
    if ! keytool -importcert -storetype PKCS12 -alias "${certBaseName%.*}" -file "$cert" -keystore "$SERVER_TRUSTSTORE" -storepass "$password" -noprompt -trustcacerts; then
      echo "Could not import $cert into trust store."
      exit 1
    fi
  fi
done
shopt -u nullglob

export CLIENT_TRUSTSTORE="$SERVER_TRUSTSTORE"

export KUBERNETES_TRUSTSTORE_FILE="$CLIENT_TRUSTSTORE"
export KUBERNETES_TRUSTSTORE_PASSPHRASE="$TRUSTSTORE_PASSWORD"
