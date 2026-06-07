#!/usr/bin/env bash
#
# Spin up the Azure Cosmos DB vNext emulator (over HTTPS), trust its self-signed
# certificate, and run the live integration tests against it.
#
# Requirements: Docker running, and JAVA_HOME pointing at a JDK 21.
# Usage: JAVA_HOME=/path/to/jdk-21 ./scripts/run-emulator-integration-tests.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE="mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:vnext-preview"
NAME="cosmos-emu"
ENDPOINT="https://localhost:8081"
# Well-known Cosmos DB emulator key (public, not a secret).
KEY='C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw=='
TRUSTSTORE="$PWD/build/cosmos-emulator-truststore.jks"

: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21}"

echo "==> Starting emulator ($IMAGE) with PROTOCOL=https ..."
docker rm -f "$NAME" >/dev/null 2>&1 || true
# NOTE: leave GATEWAY_PUBLIC_ENDPOINT unset (defaults to 'localhost') when mapping 8081:8081 —
# passing a full URL produces a malformed advertised endpoint and breaks the SDK.
docker run -d --name "$NAME" -e PROTOCOL=https -p 8081:8081 -p 1234:1234 "$IMAGE" >/dev/null

echo "==> Waiting for the gateway to become ready ..."
curl -sk --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors --max-time 5 "$ENDPOINT/" -o /dev/null

echo "==> Trusting the emulator certificate ..."
mkdir -p build
echo | openssl s_client -connect localhost:8081 -servername localhost 2>/dev/null \
  | openssl x509 -outform PEM > build/cosmos-emulator.crt
cp "$JAVA_HOME/lib/security/cacerts" "$TRUSTSTORE"
chmod u+w "$TRUSTSTORE"
"$JAVA_HOME/bin/keytool" -importcert -keystore "$TRUSTSTORE" -storepass changeit \
  -alias cosmos-emulator -file build/cosmos-emulator.crt -noprompt >/dev/null

echo "==> Running integration tests ..."
COSMOS_TEST_ENDPOINT="$ENDPOINT" \
COSMOS_TEST_KEY="$KEY" \
COSMOS_TRUSTSTORE="$TRUSTSTORE" \
  ./gradlew test --tests '*CosmosEmulatorIntegrationTest'

echo
echo "Done. The emulator is still running as container '$NAME'."
echo "Stop & remove it with:  docker rm -f $NAME"
