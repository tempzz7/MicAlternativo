#!/usr/bin/env bash
# build.sh — pipeline de build completo do Sidemic (DIST-01/02/03).
#
# Um único comando produz dist/Sidemic-v{versionName}.apk assinado:
#   aapt2 link -> javac -> d8 -> zip -j -> zipalign -> apksigner + gates.
#
# ATENÇÃO (D-02): a keystore em KEYSTORE_PATH é a identidade permanente do app.
# PERDER A KEYSTORE = impossibilidade de publicar updates instaláveis por cima
# da versão já instalada — o Android exige o MESMO certificado para atualizar.
# Faça backup da keystore e do keystore.env fora do repositório.
#
# Uso:
#   scripts/build.sh                     # pipeline completo
#   scripts/build.sh --verify-only APK   # só preflight de ferramentas + gates
set -euo pipefail

# ── Configuração por env com defaults (D-02, D-03) ──────────────────────────
ANDROID_HOME="${ANDROID_HOME:-/tmp/claude-1000/-home-temp-problem/baf2ba44-b642-4c2a-955d-a883998e26e3/scratchpad/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
KEYSTORE_PATH="${KEYSTORE_PATH:-/home/temp/.micalternativo-keys/release.jks}"
KEYSTORE_ENV="${KEYSTORE_ENV:-/home/temp/.micalternativo-keys/keystore.env}"

VERSION_CODE=""
VERSION_NAME=""
verify_out=""

die() {
    echo "ERRO: $*" >&2
    exit 1
}

# ── Preflight (D-03, RESEARCH Pattern 3) ────────────────────────────────────
# O default do SDK vive em scratchpad de sessão — a falha mais provável em
# runs futuros é o SDK ter sumido; nomear exatamente o que falta e como corrigir.
preflight_tools() {
    [ -x "$BT/aapt2" ]     || die "aapt2 não encontrado em $BT/aapt2 — defina ANDROID_HOME apontando para um SDK com build-tools 34.0.0"
    # d8: o do build-tools 34.0.0 crasha (NPE) ao parsear classes anônimas —
    # preferir o d8 do build-tools 35.0.0 quando instalado (mesmo minSdk/target).
    if [ -f "$ANDROID_HOME/build-tools/35.0.0/d8" ]; then
        D8="$ANDROID_HOME/build-tools/35.0.0/d8"
    else
        D8="$BT/d8"
    fi
    [ -f "$D8" ]           || die "d8 não encontrado — instale build-tools 35.0.0 (recomendado) ou 34.0.0 no SDK"
    [ -x "$BT/zipalign" ]  || die "zipalign não encontrado em $BT/zipalign — defina ANDROID_HOME apontando para um SDK com build-tools 34.0.0"
    [ -f "$BT/apksigner" ] || die "apksigner não encontrado em $BT/apksigner — defina ANDROID_HOME apontando para um SDK com build-tools 34.0.0"
    [ -f "$ANDROID_JAR" ]  || die "android.jar não encontrado em $ANDROID_JAR — instale a platform android-34 no SDK"
    command -v javac >/dev/null 2>&1 || die "javac não encontrado no PATH — instale um JDK (OpenJDK 21)"
    command -v zip   >/dev/null 2>&1 || die "zip não encontrado no PATH — instale o pacote zip (Info-ZIP)"
}

warn_if_loose_perms() {
    # AVISO (não falha) se permissões forem mais frouxas que 600 (RESEARCH Open Q2)
    local f="$1" perms
    perms=$(stat -c %a "$f" 2>/dev/null || echo "")
    if [ -n "$perms" ] && [ "${perms: -2}" != "00" ]; then
        echo "AVISO: permissões de $f são $perms (mais frouxas que 600) — rode: chmod 600 $f" >&2
    fi
}

preflight_keystore() {
    [ -f "$KEYSTORE_PATH" ] || die "keystore não encontrada em $KEYSTORE_PATH — defina KEYSTORE_PATH (D-02)"
    [ -f "$KEYSTORE_ENV" ]  || die "arquivo de credenciais não encontrado em $KEYSTORE_ENV — defina KEYSTORE_ENV (D-02)"
    warn_if_loose_perms "$KEYSTORE_PATH"
    warn_if_loose_perms "$KEYSTORE_ENV"
}

# ── Versão (D-04, RESEARCH Q7 — parse CRLF-safe, Pitfall 7) ─────────────────
parse_version() {
    [ -f version.properties ] || die "version.properties não encontrado na raiz do projeto"
    VERSION_CODE=$(grep -E '^versionCode=' version.properties | head -1 | cut -d= -f2 | tr -d '[:space:]')
    VERSION_NAME=$(grep -E '^versionName=' version.properties | head -1 | cut -d= -f2 | tr -d '[:space:]')
    # ASVS V5: os valores entram no argv do aapt2 — validar antes de usar
    [[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "versionCode inválido em version.properties (esperado inteiro, obtido: '$VERSION_CODE')"
    [[ -n "$VERSION_NAME" ]] || die "versionName vazio em version.properties"
}

# ── Estágios do pipeline, ordem FIXA de D-07 ────────────────────────────────
stage_link() {
    # Com recursos (ícone): aapt2 compile --dir gera o .flat, depois link.
    # RESEARCH Q1 previa manifest-only; a marca própria trouxe res/ ao projeto.
    "$BT/aapt2" compile --dir app/src/main/res -o build/res.zip
    "$BT/aapt2" link \
        --manifest app/src/main/AndroidManifest.xml \
        -I "$ANDROID_JAR" \
        --version-code "$VERSION_CODE" \
        --version-name "$VERSION_NAME" \
        --java build/gen \
        build/res.zip \
        -o build/base.apk
}

stage_compile() {
    # RESEARCH Q2 — NUNCA combinar --release 8 com bootclasspath (Pitfall 4).
    # Fonte não usa lambdas/method refs: o android.jar no bootclasspath não tem
    # LambdaMetafactory completo, e o d8 34.0.0 crasha ao desugarar invokedynamic.
    javac \
        -source 8 -target 8 \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$ANDROID_JAR" \
        -encoding UTF-8 \
        -Xlint:-options \
        -d build/obj \
        $(find app/src/main/java build/gen -name '*.java')
}

stage_dex() {
    # RESEARCH Q3 — d8 NÃO cria o diretório de saída (Pitfall 2).
    # Classes vão num .jar (nomes com $ de classes anônimas passam sem risco).
    (cd build/obj && jar cf ../classes.jar .)
    "$D8" --release --min-api 29 --lib "$ANDROID_JAR" \
        --output build/dex \
        build/classes.jar
}

stage_native() {
    # Motor de autotune (app/src/main/cpp) — compilado para as ABIs que cobrem
    # os aparelhos Android atuais. Sem NDK, o app cai no autotune em Java.
    local ndk_root="$ANDROID_HOME/ndk"
    local ndk
    ndk=$(ls -d "$ndk_root"/* 2>/dev/null | sort -V | tail -1)
    if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
        echo "AVISO: NDK não encontrado em $ndk_root — APK sairá sem libsidemic.so (autotune nativo indisponível)" >&2
        return 0
    fi
    local tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
    mkdir -p build/jni/arm64-v8a build/jni/armeabi-v7a
    "$tc/aarch64-linux-android29-clang++" -O3 -fPIC -shared -std=c++17 -fvisibility=hidden \
        -o build/jni/arm64-v8a/libsidemic.so app/src/main/cpp/autotune.cpp -lm
    "$tc/armv7a-linux-androideabi29-clang++" -O3 -fPIC -shared -std=c++17 -fvisibility=hidden \
        -o build/jni/armeabi-v7a/libsidemic.so app/src/main/cpp/autotune.cpp -lm
}

stage_pack() {
    # RESEARCH Q4 — somente APPEND com zip -j; jamais reempacotar a árvore
    # (recomprimiria resources.arsc, que deve ficar Stored — Pitfall 5)
    cp build/base.apk build/unsigned.apk
    (cd build/dex && zip -j ../unsigned.apk classes.dex)
    # .so entram como lib/<abi>/ preservando o caminho (zip sem -j).
    # Guardadas sem compressão: o loader mapeia direto do APK (extractNativeLibs=false).
    if [ -d build/jni ]; then
        mkdir -p build/libtree/lib
        cp -r build/jni/* build/libtree/lib/
        (cd build/libtree && zip -0 -r ../unsigned.apk lib)
    fi
}

stage_align() {
    # RESEARCH Q5 — incondicional, SEMPRE antes da assinatura (D-07, Pitfall 6)
    "$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
}

stage_sign() {
    # RESEARCH Q5 / Pattern 2 — senha exclusivamente via ambiente (env:STOREPASS),
    # nunca em argv (visível em ps), nunca ecoada em log (D-01, T-01-01)
    set -a; source "$KEYSTORE_ENV"; set +a
    : "${STOREPASS:?ERRO: STOREPASS ausente em $KEYSTORE_ENV}"
    : "${ALIAS:?ERRO: ALIAS ausente em $KEYSTORE_ENV}"
    "$BT/apksigner" sign \
        --ks "$KEYSTORE_PATH" \
        --ks-key-alias "$ALIAS" \
        --ks-pass env:STOREPASS \
        --min-sdk-version 29 \
        --out "dist/Sidemic-v${VERSION_NAME}.apk" \
        build/aligned.apk
}

# ── Gates (D-08, DIST-02) — rodam no ARTEFATO FINAL; nada o modifica depois ──
run_gates() {
    local apk="$1"
    [ -f "$apk" ] || die "APK não encontrado: $apk"

    # Gate 1: assinatura — exit code checado diretamente na captura (Pattern 1:
    # nunca encadear o gate em pipe antes de ler o exit; um pipe reportaria o
    # exit do consumidor — Pitfall 3)
    if ! verify_out=$("$BT/apksigner" verify --verbose --min-sdk-version 29 "$apk"); then
        die "APK sem assinatura v2/v3 — não instalará no Android 11+ ($apk reprovado no apksigner verify)"
    fi
    # v3 satisfaz o "v2+" do DIST-02 (Pitfall 1: minSdk 29 => v3-only por design)
    echo "$verify_out" | grep -Eq '(v2 scheme \(APK Signature Scheme v2\): true|v3 scheme \(APK Signature Scheme v3\): true)' \
        || die "APK sem assinatura v2/v3 — não instalará no Android 11+ (nenhum esquema v2/v3 true)"

    # Gate 2: alinhamento — invocação bare, exit code direto
    "$BT/zipalign" -c 4 "$apk" || die "APK desalinhado (zipalign -c 4 reprovou $apk)"
}

# ── Resumo (D-08, DIST-03) ──────────────────────────────────────────────────
print_summary() {
    local apk="$1"
    echo ""
    echo "═══════════════════════ RESUMO DO BUILD ═══════════════════════"
    echo "APK:    $apk ($(stat -c %s "$apk") bytes)"
    echo "SHA256: $(sha256sum "$apk" | cut -d' ' -f1)"
    echo "─── Esquemas de assinatura (apksigner verify) ───"
    echo "$verify_out" | grep -E 'scheme' || true
    echo "─── Certificado (continuidade DIST-03) ───"
    "$BT/apksigner" verify --print-certs "$apk" | grep 'SHA-256'
    echo "─── Badging (aapt2) ───"
    "$BT/aapt2" dump badging "$apk" | grep -E "^package:|^sdkVersion|^targetSdkVersion|launchable-activity" || true
    echo "═══════════════════════════════════════════════════════════════"
}

# ── main ────────────────────────────────────────────────────────────────────
main() {
    if [ "${1:-}" = "--verify-only" ]; then
        [ -n "${2:-}" ] || die "uso: scripts/build.sh --verify-only <apk>"
        preflight_tools
        run_gates "$2"
        print_summary "$2"
        echo "OK: $2 passou nos gates de assinatura e alinhamento"
        return 0
    fi

    preflight_tools
    preflight_keystore
    parse_version

    # Limpar intermediários e preparar diretórios (Pitfall 2)
    rm -rf build
    mkdir -p build/obj build/dex build/gen dist

    stage_link
    stage_compile
    stage_dex
    stage_native
    stage_pack
    stage_align
    stage_sign

    local apk="dist/Sidemic-v${VERSION_NAME}.apk"
    run_gates "$apk"
    print_summary "$apk"
    echo "OK: build completo — $apk"
}

main "$@"
