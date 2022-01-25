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

# https://jamielinux.com/docs/openssl-certificate-authority/create-the-root-pair.html
# https://stackoverflow.com/a/48029376/5793905

# shellcheck disable=SC2016
set -e

OUT_PATH="$( cd -- "$1" >/dev/null 2>&1 ; pwd -P )"

SCRIPT_PATH="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 ; pwd -P )"
cd "$SCRIPT_PATH"

PASSWORD=$(openssl rand -hex 32)

BASE_CONF='[ policy_strict ]
# The root CA should only sign intermediate certificates that match.
# See the POLICY FORMAT section of `man ca`.
countryName             = match
stateOrProvinceName     = match
organizationName        = match
organizationalUnitName  = optional
commonName              = supplied
emailAddress            = optional

[ policy_loose ]
# Allow the intermediate CA to sign a more diverse range of certificates.
# See the POLICY FORMAT section of the `ca` man page.
countryName             = optional
stateOrProvinceName     = optional
localityName            = optional
organizationName        = optional
organizationalUnitName  = optional
commonName              = supplied
emailAddress            = optional

[ req ]
# Options for the `req` tool (`man req`).
default_bits        = 2048
distinguished_name  = req_distinguished_name
string_mask         = utf8only

# SHA-1 is deprecated, so use SHA-2 instead.
default_md          = sha256

# Extension to add when the -x509 option is used.
x509_extensions     = v3_ca

[ req_distinguished_name ]
# See <https://en.wikipedia.org/wiki/Certificate_signing_request>.
countryName                     = Country Name (2 letter code)
stateOrProvinceName             = State or Province Name
localityName                    = Locality Name
0.organizationName              = Organization Name
organizationalUnitName          = Organizational Unit Name
commonName                      = Common Name
emailAddress                    = Email Address

# Optionally, specify some defaults.
countryName_default             = DE
stateOrProvinceName_default     = Baden-Wuerttemberg
localityName_default            = Stuttgart
0.organizationName_default      = Micro Focus
organizationalUnitName_default  = ITOM

[ v3_ca ]
# Extensions for a typical CA (`man x509v3_config`).
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints = critical, CA:true
keyUsage = critical, digitalSignature, cRLSign, keyCertSign

[ v3_intermediate_ca ]
# Extensions for a typical intermediate CA (`man x509v3_config`).
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, digitalSignature, cRLSign, keyCertSign

[ flork_cert ]
basicConstraints = CA:FALSE
nsCertType = server, client
nsComment = "OpenSSL Generated Server/Client Certificate"
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
keyUsage = critical, digitalSignature, keyEncipherment, nonRepudiation
extendedKeyUsage = serverAuth, clientAuth
subjectAltName = @alt_names

[ alt_names ]
DNS.1 = *.flork.svc

[ crl_ext ]
# Extension for CRLs (`man x509v3_config`).
authorityKeyIdentifier=keyid:always

[ ocsp ]
# Extension for OCSP signing certificates (`man ocsp`).
basicConstraints = CA:FALSE
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, OCSPSigning'

# ROOT PAIR
# ======================================================================================================================

mkdir ca
echo '[ ca ]
default_ca = CA_default

[ CA_default ]
# Directory and file locations.
dir               = '"$SCRIPT_PATH/ca"'
certs             = $dir/certs
crl_dir           = $dir/crl
new_certs_dir     = $dir/newcerts
database          = $dir/index.txt
serial            = $dir/serial
RANDFILE          = $dir/private/.rand

# The root key and root certificate.
private_key       = $dir/private/ca.key.pem
certificate       = $dir/certs/ca.cert.pem

# For certificate revocation lists.
crlnumber         = $dir/crlnumber
crl               = $dir/crl/ca.crl.pem
crl_extensions    = crl_ext
default_crl_days  = 30

# SHA-1 is deprecated, so use SHA-2 instead.
default_md        = sha256

name_opt          = ca_default
cert_opt          = ca_default
default_days      = 375
preserve          = no
policy            = policy_strict

' >ca/openssl.cnf
echo "$BASE_CONF" >>ca/openssl.cnf

cd ca
mkdir certs crl newcerts private
chmod 700 private
touch index.txt
echo 1000 >serial

openssl genrsa -aes256 -passout file:<(echo -n "$PASSWORD") -out private/ca.key.pem 4096
chmod 400 private/ca.key.pem

printf "\n\n\n\n\nFlork root CA\n\n" | openssl req \
  -config openssl.cnf \
  -key private/ca.key.pem \
  -passin file:<(echo -n "$PASSWORD") \
  -new -x509 -days 1095 -sha256 -extensions v3_ca \
  -out certs/ca.cert.pem

# INTERMEDIATE PAIR
# ======================================================================================================================

mkdir intermediate
(
  cd intermediate
  mkdir certs crl csr newcerts private
  chmod 700 private
  touch index.txt
  echo 1000 >serial
  echo 1000 >crlnumber
)

echo '[ ca ]
default_ca = CA_default

[ CA_default ]
# Directory and file locations.
dir               = '"$SCRIPT_PATH/ca/intermediate"'
certs             = $dir/certs
crl_dir           = $dir/crl
new_certs_dir     = $dir/newcerts
database          = $dir/index.txt
serial            = $dir/serial
RANDFILE          = $dir/private/.rand

# The root key and root certificate.
private_key       = $dir/private/intermediate.key.pem
certificate       = $dir/certs/intermediate.cert.pem

# For certificate revocation lists.
crlnumber         = $dir/crlnumber
crl               = $dir/crl/intermediate.crl.pem
crl_extensions    = crl_ext
default_crl_days  = 30

# SHA-1 is deprecated, so use SHA-2 instead.
default_md        = sha256

name_opt          = ca_default
cert_opt          = ca_default
default_days      = 375
preserve          = no
policy            = policy_loose

' >intermediate/openssl.cnf
echo "$BASE_CONF" >>intermediate/openssl.cnf

openssl genrsa -aes256 -passout file:<(echo -n "$PASSWORD") -out intermediate/private/intermediate.key.pem 4096
chmod 400 intermediate/private/intermediate.key.pem

printf "\n\n\n\n\nFlork intermediate CA\n\n" | openssl req \
  -config intermediate/openssl.cnf -new -sha256 \
  -key intermediate/private/intermediate.key.pem \
  -passin file:<(echo -n "$PASSWORD") \
  -out intermediate/csr/intermediate.csr.pem

openssl ca -config openssl.cnf -extensions v3_intermediate_ca \
  -days 900 -notext -md sha256 \
  -in intermediate/csr/intermediate.csr.pem \
  -passin file:<(echo -n "$PASSWORD") -batch \
  -out intermediate/certs/intermediate.cert.pem

chmod 444 intermediate/certs/intermediate.cert.pem

# SERVER PAIR
# ======================================================================================================================

openssl genrsa -out intermediate/private/flork.key.pem 2048
chmod 400 intermediate/private/flork.key.pem

printf "\n\n\n\n\nitom-flork-admission-svc.flork.svc\n\n" | openssl req \
  -config intermediate/openssl.cnf -new -sha256 \
  -key intermediate/private/flork.key.pem \
  -passin file:<(echo -n "$PASSWORD") \
  -out intermediate/csr/flork.csr.pem

openssl ca -config intermediate/openssl.cnf \
  -extensions flork_cert -days 900 -notext -md sha256 \
  -in intermediate/csr/flork.csr.pem \
  -passin file:<(echo -n "$PASSWORD") -batch \
  -out intermediate/certs/flork.cert.pem
chmod 444 intermediate/certs/flork.cert.pem

# FINISH
# ======================================================================================================================

cp -f certs/ca.cert.pem "$OUT_PATH/ca.crt"
cp -f intermediate/private/flork.key.pem "$OUT_PATH/server.key" && chmod 444 "$OUT_PATH/server.key"
cp -f intermediate/certs/flork.cert.pem "$OUT_PATH/server.crt" && chmod 774 "$OUT_PATH/server.crt"
cat intermediate/certs/intermediate.cert.pem >>"$OUT_PATH/server.crt" && chmod 444 "$OUT_PATH/server.crt"
