#!/usr/bin/env bash
# test_build.sh — harness E2E independente do Sidemic.
#
# Contrato do build (DIST-01/02/03): roda scripts/build.sh e depois re-verifica,
# POR FORA e de forma independente, os gates de assinatura, alinhamento, badging
# e continuidade do certificado no artefato final.
set -euo pipefail

# Mesmos defaults do build.sh (D-03)
ANDROID_HOME="${ANDROID_HOME:-/tmp/claude-1000/-home-temp-problem/baf2ba44-b642-4c2a-955d-a883998e26e3/scratchpad/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"

# Digest SHA-256 do certificado permanente do projeto (DIST-03)
CERT_DIGEST="b6966a76565821025c2ed22ea8325e4108c9beff70686baacb5dee496b8d6fe8"

fail() {
    echo "TEST FALHOU: $*" >&2
    exit 1
}

# (b) build.sh precisa existir e ser executável — estado RED enquanto não existir
if [ ! -x scripts/build.sh ]; then
    fail "scripts/build.sh não existe ou não é executável — o pipeline de build ainda não foi implementado"
fi

# (c) rodar o build completo
if ! scripts/build.sh; then
    fail "scripts/build.sh terminou com erro (exit != 0)"
fi

# (d) parse CRLF-safe da versão (RESEARCH Q7 / Pitfall 7)
VERSION_NAME=$(grep -E '^versionName=' version.properties | head -1 | cut -d= -f2 | tr -d '[:space:]')
[ -n "$VERSION_NAME" ] || fail "versionName vazio em version.properties"
APK="dist/Sidemic-v${VERSION_NAME}.apk"
[ -f "$APK" ] || fail "artefato final $APK não existe"

# (e) Gate independente 1: assinatura — captura em variável, NUNCA em pipe
# (Pitfall 3: pipe reporta o exit do último comando, engolindo a falha do apksigner)
verify_out=$("$BT/apksigner" verify --verbose --min-sdk-version 29 "$APK") \
    || fail "apksigner verify reprovou $APK"
# Aceitar v2 OU v3 (Pitfall 1: minSdk 29 emite v3-only por design)
echo "$verify_out" | grep -Eq '(v2 scheme \(APK Signature Scheme v2\): true|v3 scheme \(APK Signature Scheme v3\): true)' \
    || fail "nenhum esquema v2/v3 true na saída do apksigner verify"

# (f) Gate independente 2: alinhamento — invocação bare, exit code direto
"$BT/zipalign" -c 4 "$APK" || fail "zipalign -c 4 reprovou $APK"

# (g) Badging: identidade do APK (D-05, D-06)
badging=$("$BT/aapt2" dump badging "$APK") || fail "aapt2 dump badging falhou em $APK"
echo "$badging" | grep -q "name='com.sidemic'" || fail "badging sem package com.sidemic"
echo "$badging" | grep -q "sdkVersion:'29'" || fail "badging sem sdkVersion 29"
echo "$badging" | grep -q "targetSdkVersion:'34'" || fail "badging sem targetSdkVersion 34"
echo "$badging" | grep -q "android.permission.RECORD_AUDIO" || fail "badging sem permissão RECORD_AUDIO"
echo "$badging" | grep -q "launchable-activity: name='com.sidemic.MainActivity'" \
    || fail "badging sem launchable-activity MainActivity"

# (h) Certificado permanente (DIST-03): digest deve ser o do projeto
certs_out=$("$BT/apksigner" verify --print-certs "$APK") || fail "apksigner --print-certs falhou em $APK"
echo "$certs_out" | grep -q "$CERT_DIGEST" \
    || fail "digest do certificado difere do certificado permanente do projeto ($CERT_DIGEST)"

# (i) sucesso
echo "TEST OK: $APK"
