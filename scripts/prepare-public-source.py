#!/usr/bin/env python3
"""Create a scanned source snapshot, never a clone or a publication operation."""

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile


ROOT_FILES = {
    ".gitattributes", ".gitignore", ".gitleaks.toml", ".wrapperguard-baseline.json",
    "README.md", "LICENSE", "NOTICE.md", "SECURITY.md", "PRIVACY.md",
    "CONTRIBUTING.md", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat",
}
EXTENSIONS = {
    ".kt", ".kts", ".java", ".c", ".cpp", ".h", ".map", ".xml", ".toml",
    ".properties", ".md", ".txt", ".png", ".webp", ".h264", ".aac", ".alac",
    ".pcm", ".sh", ".py", ".rb", ".cjs", ".gradle", ".yml", ".yaml",
}
ALLOWED_ROOTS = {"app", "gradle", "scripts", "docs", "design", ".github"}
BLOCKED_PARTS = {".git", ".idea", ".codex", ".agents", ".gradle", ".cxx",
                 "build", "releases", "private", "downloads", "__pycache__"}
LEGACY_SPIKE_SOURCES = {
    "app/src/main/cpp/airplay_server.cpp", "app/src/main/cpp/airplay_server.h",
    "app/src/main/cpp/receiver_jni.cpp", "app/src/main/cpp/video_decoder_android.cpp",
    "app/src/main/cpp/video_decoder_android.h", "app/src/main/cpp/h264/h264_avcc_parser.cpp",
    "app/src/main/cpp/h264/h264_avcc_parser.h",
}


def allowed(name):
    path = pathlib.PurePosixPath(name)
    if name in LEGACY_SPIKE_SOURCES:
        return False
    if path.is_absolute() or ".." in path.parts or not path.parts:
        return False
    if any(part in BLOCKED_PARTS for part in path.parts):
        return False
    if len(path.parts) == 1:
        return name in ROOT_FILES
    if path.parts[0] not in ALLOWED_ROOTS:
        return False
    if path.name in {"local.properties", "secrets.properties", "store-password"} or "signing.properties" in path.name:
        return False
    if path.name.startswith(".env"):
        return False
    return (name in {"app/.gitignore", "gradle/wrapper/gradle-wrapper.jar", "docs/dependency-inventory.json"}
            or path.name in {"CMakeLists.txt", "LICENSE", "COPYING", "NOTICE", "AUTHORS"}
            or path.suffix in EXTENSIONS)


def export(root, output, scanner):
    root = root.resolve()
    output = output.absolute()
    if output.exists() or output.is_symlink():
        raise ValueError("Output must not exist; existing files are never replaced")
    output = output.resolve()
    if output == root or root.is_relative_to(output):
        raise ValueError("Output cannot contain the source repository")
    candidates = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root)
    names = sorted({name.decode() for name in candidates.split(b"\0") if name})
    selected, omitted = [], []
    for name in names:
        if not allowed(name):
            omitted.append(name)
            continue
        source = root / name
        if source.is_symlink() or not source.resolve().is_relative_to(root):
            raise ValueError(f"Refusing symlink or out-of-tree source: {name}")
        if not source.exists():
            continue  # A tracked file removed in the working tree is not exported.
        if not source.is_file() or source.stat().st_size > 10 * 1024 * 1024:
            raise ValueError(f"Refusing special or oversized source: {name}")
        selected.append(name)
    if "app/src/main/AndroidManifest.xml" not in selected or "README.md" not in selected:
        raise ValueError("Source set is incomplete")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = pathlib.Path(tempfile.mkdtemp(prefix=".fossmirror-source-", dir=output.parent))
    entries = []
    try:
        for name in selected:
            source, target = root / name, staging / name
            target.parent.mkdir(parents=True, exist_ok=True)
            content = source.read_bytes()
            target.write_bytes(content)
            target.chmod(0o755 if source.stat().st_mode & 0o111 else 0o644)
            entries.append({"path": name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()})
        manifest = {
            "base_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root).decode().strip(),
            "working_tree_snapshot": True,
            "history_included": False,
            "publication_approved": False,
            "files": entries,
            "omitted_paths": omitted,
        }
        (staging / "SOURCE_MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")
        # Always scan copied bytes. Do not rely on ignore files or a previous audit.
        subprocess.run([str(scanner), "dir", "--redact=100", "--no-banner", "--no-color",
                        "--ignore-gitleaks-allow", "--max-archive-depth=2",
                        "--max-decode-depth=2", str(staging)], check=True)
        staging.rename(output)
    finally:
        if staging.exists():
            shutil.rmtree(staging)  # Only this invocation's newly created staging directory.
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--gitleaks", default="gitleaks")
    args = parser.parse_args()
    scanner = shutil.which(args.gitleaks)
    if not scanner:
        parser.error("Install Gitleaks or supply --gitleaks; refusing an unscanned export")
    root = pathlib.Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"]).decode().strip())
    if pathlib.Path.cwd().resolve() != root:
        parser.error("Run this from the repository root")
    try:
        manifest = export(root, args.output, pathlib.Path(scanner).resolve())
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Export refused: {error}\n")
    print(f"Scanned {len(manifest['files'])} files into {args.output}")
    print("No Git history or keys copied. Read docs/PUBLIC_RELEASE.md before publishing.")


if __name__ == "__main__":
    main()
