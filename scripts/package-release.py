#!/usr/bin/env python3
"""Verify signed ABI APKs and package corresponding committed source for release."""

import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import tempfile
import zipfile


ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def release_outputs(metadata):
    if metadata.get("applicationId") != "com.sal7one.tvtestproject" or metadata.get("variantName") != "release":
        raise ValueError("Expected FossMirrorTV release output metadata")
    outputs, versions = {}, set()
    for element in metadata["elements"]:
        filters = element["filters"]
        if not filters:
            abi = "universal"
        elif len(filters) == 1 and filters[0]["filterType"] == "ABI" and filters[0]["value"] in ABIS:
            abi = filters[0]["value"]
        else:
            raise ValueError("Unexpected APK filters")
        name = element["outputFile"]
        if abi in outputs or pathlib.PurePath(name).name != name or not name.endswith(".apk"):
            raise ValueError("Duplicate ABI or invalid APK filename")
        version = element["versionName"]
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?", version):
            raise ValueError("Unsafe release version")
        versions.add((version, element["versionCode"]))
        outputs[abi] = name
    if set(outputs) != ABIS | {"universal"} or len(versions) != 1:
        raise ValueError("Require four ABI APKs and one universal APK of the same version")
    return next(iter(versions)), outputs


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(*args, cwd=None):
    return subprocess.check_output([str(arg) for arg in args], cwd=cwd, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-tools", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    if command("git", "status", "--porcelain", cwd=root).strip():
        parser.error("Commit all source changes before packaging corresponding source")
    revision = command("git", "rev-parse", "HEAD", cwd=root).strip()
    apk_dir = root / "app/build/outputs/apk/release"
    (version, code), outputs = release_outputs(json.loads((apk_dir / "output-metadata.json").read_text()))
    destination = root / "releases" / f"v{version}"
    destination.mkdir(parents=True, exist_ok=True)
    signer = None
    records = []
    with tempfile.TemporaryDirectory(prefix=".release-", dir=destination) as directory:
        staging = pathlib.Path(directory)
        for abi, filename in sorted(outputs.items()):
            apk = apk_dir / filename
            details = command(args.build_tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
            fingerprint = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", details)
            if not fingerprint or (signer is not None and signer != fingerprint[1]):
                raise ValueError("Missing or inconsistent APK signing certificate")
            signer = fingerprint[1]
            command(args.build_tools / "zipalign", "-c", "-P", "16", "4", apk)
            badging = command(args.build_tools / "aapt", "dump", "badging", apk)
            if "application-debuggable" in badging or f"versionName='{version}'" not in badging or f"versionCode='{code}'" not in badging:
                raise ValueError("APK is debuggable or version does not match metadata")
            with zipfile.ZipFile(apk) as archive:
                native_abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
            if native_abis != (ABIS if abi == "universal" else {abi}):
                raise ValueError("APK native libraries do not match its ABI label")
            name = f"FossMirrorTV-{version}-{abi}.apk"
            shutil.copyfile(apk, staging / name)
            records.append({"file": name, "abi": abi, "bytes": apk.stat().st_size, "sha256": sha256(apk)})
        source_name = f"FossMirrorTV-{version}-source.zip"
        command("git", "archive", "--format=zip", f"--prefix=FossMirrorTV-{version}/",
                f"--output={staging / source_name}", revision, cwd=root)
        manifest = {"version": version, "version_code": code, "source_revision": revision,
                    "certificate_sha256": signer, "apks": records,
                    "source": {"file": source_name, "sha256": sha256(staging / source_name)}}
        (staging / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        files = sorted(staging.iterdir())
        (staging / "SHA256SUMS").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in files))
        for path in staging.iterdir():
            target = destination / path.name
            if target.exists() and sha256(target) != sha256(path):
                raise ValueError(f"Refusing to overwrite different release bytes: {path.name}")
        for path in staging.iterdir():
            shutil.copyfile(path, destination / path.name)
            print(f"Release asset: {path.name}")


if __name__ == "__main__":
    main()
