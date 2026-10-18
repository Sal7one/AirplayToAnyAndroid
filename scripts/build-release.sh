#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
TOOLS="$SDK/build-tools/36.0.0"

if [[ -z "${FOSSMIRROR_KEYSTORE:-}" ]]; then
    PRIVATE="$HOME/.android/fossmirrortv-release"
    KEYSTORE="$PRIVATE/release.p12"
    PASSWORD_FILE="$PRIVATE/store-password"
    mkdir -p "$PRIVATE"
    chmod 700 "$PRIVATE"
    if [[ ! -e "$KEYSTORE" && ! -e "$PASSWORD_FILE" ]]; then
        openssl rand -base64 -out "$PASSWORD_FILE" 48
        export FOSSMIRROR_STORE_PASSWORD="$(tr -d '\r\n' < "$PASSWORD_FILE")"
        keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
            -alias fossmirrortv -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
            -validity 10000 -dname "CN=FossMirrorTV Release" \
            -storepass:env FOSSMIRROR_STORE_PASSWORD -keypass:env FOSSMIRROR_STORE_PASSWORD
    fi
    [[ -s "$KEYSTORE" && -s "$PASSWORD_FILE" ]] || {
        printf 'Incomplete signing material in %s; refusing to replace an existing key.\n' "$PRIVATE" >&2
        exit 1
    }
    chmod 600 "$KEYSTORE" "$PASSWORD_FILE"
    export FOSSMIRROR_KEYSTORE="$KEYSTORE"
    export FOSSMIRROR_STORE_PASSWORD="$(tr -d '\r\n' < "$PASSWORD_FILE")"
    export FOSSMIRROR_KEY_ALIAS=fossmirrortv
fi
: "${FOSSMIRROR_STORE_PASSWORD:?Supply the private release keystore password}"

cd "$ROOT"
./gradlew :app:assembleRelease :app:testDebugUnitTest -PsplitApks=true --console=plain "$@"
python3 scripts/package-release.py --build-tools "$TOOLS"
printf 'Keep the release keystore and its password private and securely backed up.\n'
