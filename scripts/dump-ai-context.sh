#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-}"

emit() {
  if [[ -n "$OUT" ]]; then
    printf '%s\n' "$*" >> "$OUT"
  else
    printf '%s\n' "$*"
  fi
}

emit_file() {
  local file="$1"
  local lang="$2"

  emit ""
  emit "## $file"
  emit ""
  emit "\`\`\`$lang"
  sed -n '1,2400p' "$ROOT/$file" | if [[ -n "$OUT" ]]; then cat >> "$OUT"; else cat; fi
  emit ""
  emit "\`\`\`"
}

lang_for() {
  case "$1" in
    *.kt|*.kts) printf 'kotlin' ;;
    *.cpp|*.h) printf 'cpp' ;;
    *.xml) printf 'xml' ;;
    *.md) printf 'markdown' ;;
    *.toml) printf 'toml' ;;
    *) printf 'text' ;;
  esac
}

list_project_files() {
  if command -v rg >/dev/null 2>&1; then
    rg --files \
      -g '!build/**' \
      -g '!app/build/**' \
      -g '!app/.cxx/**' \
      -g '!gradle/wrapper/gradle-wrapper.jar' \
      -g '!local.properties' \
      -g '!ai-review*.md' \
      -g '!*.webp' \
      -g '!*.h264'
  else
    find . -type f \
      ! -path './.git/*' \
      ! -path './.gradle/*' \
      ! -path './.idea/*' \
      ! -path './build/*' \
      ! -path './app/build/*' \
      ! -path './app/.cxx/*' \
      ! -path './gradle/wrapper/gradle-wrapper.jar' \
      ! -name 'local.properties' \
      ! -name 'ai-review*.md' \
      ! -name '*.webp' \
      ! -name '*.h264' \
      -print | sed 's#^\./##'
  fi
}

list_source_files() {
  if command -v rg >/dev/null 2>&1; then
    rg --files \
      -g '*.kt' \
      -g '*.kts' \
      -g '*.cpp' \
      -g '*.h' \
      -g '!build/**' \
      -g '!app/build/**' \
      -g '!app/.cxx/**' \
      -g '!local.properties' \
      -g '!ai-review*.md'
  else
    find . -type f \
      ! -path './.git/*' \
      ! -path './.gradle/*' \
      ! -path './.idea/*' \
      ! -path './build/*' \
      ! -path './app/build/*' \
      ! -path './app/.cxx/*' \
      ! -name 'local.properties' \
      ! -name 'ai-review*.md' \
      \( -name '*.kt' -o -name '*.kts' -o -name '*.cpp' -o -name '*.h' \) \
      -print | sed 's#^\./##'
  fi
}

if [[ -n "$OUT" ]]; then
  mkdir -p "$(dirname "$OUT")"
  : > "$OUT"
fi

cd "$ROOT"

emit "# FossMirrorTV AI Review Bundle"
emit ""
emit "Generated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
emit "Repo: $ROOT"
emit ""
emit "## Review Request"
emit ""
emit "Please review this Android TV AirPlay receiver MVP in one pass."
emit "Focus on correctness, protocol risks, native lifecycle/threading bugs, decoder issues, security gaps, and the fastest path to real macOS mirror pixels."
emit ""
emit "Current state:"
emit "- Android/Kotlin app hosts a full-screen SurfaceView and starts a foreground receiver service."
emit "- mDNS advertises AirPlay/RAOP using UxPlay-like TXT values."
emit "- Native RTSP server listens on port 7000."
emit "- Native mirror stream listener currently speaks the AirPlay mirror TCP framing path."
emit "- MediaCodec can render a bundled H.264 clip and can accept queued Annex-B NAL units."
emit "- FairPlay/pairing/decryption is incomplete and should be treated as the main blocker."
emit ""

emit "## Git Status"
emit ""
emit "\`\`\`text"
git status --short --branch | if [[ -n "$OUT" ]]; then cat >> "$OUT"; else cat; fi
emit "\`\`\`"

emit ""
emit "## Project File List"
emit ""
emit "\`\`\`text"
list_project_files | sort | if [[ -n "$OUT" ]]; then cat >> "$OUT"; else cat; fi
emit "\`\`\`"

metadata_files=(
  "settings.gradle.kts"
  "build.gradle.kts"
  "gradle/libs.versions.toml"
  "app/build.gradle.kts"
  "app/src/main/AndroidManifest.xml"
  "app/src/main/cpp/CMakeLists.txt"
  "backlog-fast.md"
)

emit ""
emit "# Project Metadata"
for file in "${metadata_files[@]}"; do
  [[ -f "$file" ]] || continue
  emit_file "$file" "$(lang_for "$file")"
done

emit ""
emit "# Kotlin, C++, And Header Sources"
while IFS= read -r file; do
  emit_file "$file" "$(lang_for "$file")"
done < <(
  list_source_files | sort
)

emit ""
emit "# Suggested Reviewer Checklist"
emit ""
emit "- Does RTSP response shape match what macOS expects?"
emit "- Are AirPlay mirror TCP headers, payload sizes, and bplist responses correct?"
emit "- Where should UxPlay FairPlay/pairing/decryption be ported or wrapped?"
emit "- Are native threads and sockets stopped safely on service/activity teardown?"
emit "- Can MediaCodec accept the NAL format produced by the mirror path?"
emit "- What is the smallest next patch likely to produce real Mac pixels?"

if [[ -n "$OUT" ]]; then
  printf 'Wrote AI review bundle to %s\n' "$OUT"
fi
