#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
CACHE="${GRADLE_DEPENDENCY_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
OUT="${RECEIVER_TEST_OUTPUT:-$(mktemp -d "${TMPDIR:-/tmp}/fossmirror-tests.XXXXXX")}"
mkdir -p "$OUT"

cached() {
    local path
    path="$(rg --files "$CACHE/$1" -g "$2" | head -n 1)"
    test -n "$path" || { printf 'Missing cached dependency: %s\n' "$1" >&2; exit 1; }
    printf '%s' "$path"
}

JUNIT="$(cached junit/junit/4.13.2 junit-4.13.2.jar)"
HAMCREST="$(cached org.hamcrest/hamcrest-core/1.3 hamcrest-core-1.3.jar)"
BC="$(cached org.bouncycastle/bcprov-jdk18on/1.86 bcprov-jdk18on-1.86.jar)"
ANDROID="$SDK/platforms/android-36/android.jar"
test -f "$ANDROID"
mapfile_compatible_sources=()
while IFS= read -r path; do mapfile_compatible_sources+=("$path"); done < <(
    rg --files "$ROOT/app/src/main/java/com/sal7one/tvtestproject/receiver" "$ROOT/app/src/test/java/com/sal7one/tvtestproject/receiver" -g '*.kt' -g '!ReceiverRuntime.kt'
)
kotlinc -jvm-target 11 -cp "$ANDROID:$BC:$JUNIT:$HAMCREST" \
    "${mapfile_compatible_sources[@]}" \
    "$ROOT/app/src/main/java/com/sal7one/tvtestproject/DeviceIdentity.kt" \
    "$ROOT/app/src/main/java/com/sal7one/tvtestproject/UxPlayDnsTxt.kt" \
    -include-runtime -d "$OUT/receiver-tests.jar"

classes=()
while IFS= read -r path; do
    path="${path#"$ROOT/app/src/test/java/"}"
    path="${path%.kt}"
    classes+=("${path//\//.}")
done < <(rg --files "$ROOT/app/src/test/java/com/sal7one/tvtestproject/receiver" -g '*Test.kt')
java -cp "$OUT/receiver-tests.jar:$ANDROID:$BC:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore "${classes[@]}"

cmake -S "$ROOT/app/src/test/cpp" -B "$OUT/native" -DCMAKE_BUILD_TYPE=Debug
cmake --build "$OUT/native"
ctest --test-dir "$OUT/native" --output-on-failure
printf 'Host verification artifacts: %s\n' "$OUT"
