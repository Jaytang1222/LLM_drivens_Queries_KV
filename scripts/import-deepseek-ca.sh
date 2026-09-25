#!/usr/bin/env bash
# Optional: import DeepSeek / TrustAsia intermediate CA into a *user* truststore
# (no sudo). Point Java at it via:
#   export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/.kart/truststore -Djavax.net.ssl.trustStorePassword=changeit"
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
SYS_CACERTS="$JAVA_HOME/jre/lib/security/cacerts"
if [[ ! -f "$SYS_CACERTS" ]]; then
  SYS_CACERTS="$JAVA_HOME/lib/security/cacerts"
fi
OUT_DIR="${HOME}/.kart"
OUT_STORE="$OUT_DIR/truststore"
ALIAS="trustasia-dv-tls-rsa-ca-2025"
mkdir -p "$OUT_DIR"
if [[ ! -f "$OUT_STORE" ]]; then
  cp "$SYS_CACERTS" "$OUT_STORE"
fi
if keytool -list -keystore "$OUT_STORE" -storepass changeit -alias "$ALIAS" >/dev/null 2>&1; then
  echo "CA already installed in $OUT_STORE ($ALIAS)"
else
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  echo | openssl s_client -showcerts -connect api.deepseek.com:443 -servername api.deepseek.com 2>/dev/null \
    | awk '/BEGIN CERTIFICATE/{i++} {print > "'"$TMP"'/cert"i".pem"}'
  INTER="$TMP/cert2.pem"
  [[ -s "$INTER" ]] || INTER="$TMP/cert1.pem"
  keytool -importcert -noprompt -alias "$ALIAS" -file "$INTER" \
    -keystore "$OUT_STORE" -storepass changeit
  echo "installed $ALIAS into $OUT_STORE"
fi
echo "export JAVA_TOOL_OPTIONS=\"-Djavax.net.ssl.trustStore=$OUT_STORE -Djavax.net.ssl.trustStorePassword=changeit\${JAVA_TOOL_OPTIONS:+ \$JAVA_TOOL_OPTIONS}\""
