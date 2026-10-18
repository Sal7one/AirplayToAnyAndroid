# Public Release Preparation

Audit started 2026-10-18 against the original private checkout and continued on
2026-10-19. This repository, [AirplayToAnyAndroid](https://github.com/Sal7one/AirplayToAnyAndroid),
is the maintainer-approved, source-only public publication with a new Git history
and GitHub noreply commit identity. The original private repository is preserved;
its history, tags, recovery data, signing keys, and release assets were not imported.
**Publishing source is not approval to publish a stable APK or a Play Store app.**

The findings below describe the original checkout audit, not objects in this
fresh repository. The temporary exporter manifest is deliberately not committed:
it describes the pre-publication snapshot, not subsequent documentation edits.

## Findings

| Area | Result / Action |
| --- | --- |
| Credentials in history | Gitleaks 8.30.1 scanned all 34 local commits including refs/reflogs, with default rules, decoding and full redaction: no findings. This is not proof that no secret exists. |
| All local objects | 985 objects inventoried: 423 blobs, 522 trees, 34 commits, 6 tags. 384 text blobs inspected independently for sensitive paths/metadata. Binary/large contents are separately scoped. |
| Downloaded books | Two oversized PDFs remain in a local recovery ref, outside normal branches/tags/remotes. They are not in the current tree or advertised remote refs. **Never mirror-push this checkout or copy its `.git`.** Recovery data was preserved. |
| Author identity | Existing history has a non-noreply email. A new source-only repository avoids importing it. Deleting files or setting a new email does not change old commits. |
| Historical machine paths | Four old text blobs under AI-review/test-note filenames contain local home-directory paths. None of those files is in the current source snapshot; a new repository avoids importing those historical versions. |
| IDE / extra project | `.idea` is now ignored/untracked while local files remain. Two empty accidental root files were removed. The clean exporter omits AnyFlip, IDE files, caches, captures and release outputs. AnyFlip remains local and in old history. |
| Legacy spike code | Seven old RTSP/decoder/JNI/avcC source files are not referenced by the active CMake targets. They remain in the original checkout/history but are omitted from the new-repo snapshot; active protocol/media/JNI sources and tests are included. |
| Wrapper | Committed Gradle 9.1.0 wrapper JAR SHA-256 matches Gradle's published checksum; the distribution ZIP hash is pinned. |
| Crypto provenance | All seven PlayFair files and the retained GPLv3 text exactly match pinned UxPlay blobs; see [crypto notes](protocol/crypto-source.md). This is provenance, not a crypto audit. |
| Project license | Maintainer approved GPLv3 on 2026-10-19. Root `LICENSE` and `NOTICE.md` state GPL-3.0-only for the combined work while preserving component licenses. Corresponding-source distribution remains required. |
| Dependencies | Bouncy Castle 1.79 returned four OSV advisories. Updated to 1.86; all 64 resolved runtime coordinates now return no listed OSV advisories. This is version matching, not reachability analysis or a security guarantee. |
| Product security | No PIN/trusted-device authorization. Use only on a trusted LAN; never port-forward. [Security](../SECURITY.md) and [privacy](../PRIVACY.md) describe the limits. |
| Remote surfaces | At audit time: private repo, one advertised branch, six tags, five prereleases, no forks, no issues/PRs, no Actions runs, no Pages. Wiki feature is enabled; wiki content was not independently audited. |
| Published APK contents | Six local APKs match the actual GitHub asset hashes. Five release variants are non-debuggable; the explicitly named alpha.6 debug variant is debuggable. ZIP entry inventories found no signing stores, keys, PDFs/captures or local-properties files, and no home-directory paths in entry contents. These are targeted checks, not proof of no embedded data. |
| Images / media | Both README screenshots were inspected as synthetic emulator output. All 48 frames of the bundled H.264 clip show a generated test pattern. Runtime WebPs/screenshots have no EXIF/XMP/text metadata chunks; AI source PNGs retain C2PA provenance chunks, not stripped or fully decoded by this audit. |

## Still Required Before A Binary Release

1. Retain the approved GPL-3.0-only root `LICENSE`, component notices and
   corresponding-source distribution plan. Do not invent an "or later" grant
   for third-party files. Existing per-file licenses remain. Complete any
   distribution/jurisdiction review needed for your use; this checklist is
   engineering preparation, not legal advice.
2. Keep the README source-first. Add a download button only after publishing a
   matching public release; do not link an old private binary as this source's APK.
3. Preserve the clean history. Do not migrate old tags, refs, `.git`, or private
   issue/release history. Use a GitHub-provided noreply email for public commits.
4. Build a new version from the final clean source, tag that exact source, and
   publish corresponding source alongside the non-debuggable release APK.
   Existing alpha.6 APKs predate the crypto update and are not binaries of this
   snapshot. Do not replace the old tag/assets with different bytes.
5. Keep private vulnerability reporting and available secret scanning/push
   protection enabled. Review repository settings, release notes, attachments,
   and Actions permissions for each release.
6. Re-run host/native/Android checks after final licensing/notices/packaging.
   The crypto update passes the host suite and APK builds; **new ART, TV/Mac
   interoperability, and extended audio/Firefox checks remain separate gates**.

Do not publish the development/debug APK as the general download. It can
produce a one-shot private diagnostic frame when explicitly triggered locally.
Release certificates/public keys in an APK are public, not leaked private keys.

## Safe New Repository Path

Run from the original repository root, with Python 3.9+ and Gitleaks installed:

```sh
python3 scripts/prepare-public-source.py --output build/public-source
```

Use `--gitleaks /path/to/gitleaks` if it is not on PATH. Existing destinations
are refused. The tool copies only selected working-tree source, scans the copied
bytes, writes per-file SHA-256 hashes in `SOURCE_MANIFEST.json`, and includes
no `.git`, private captures, signing files, downloads, local properties or APKs.
It does not create a repo, choose a license, push, or change visibility. Review
the source manifest and the gates above; this is a candidate snapshot, not
automated legal/security clearance. Do not merge old history back into it.

This public repository was initialized separately from the source snapshot.
For future independent exports, review the manifest and copied bytes before
publishing; never use `git push --mirror` to seed another repository. The old
private repo can remain as a backup. A new repository does not revoke a leaked
credential or erase data in old clones/caches.

## Reproduce The Checks

```sh
gitleaks git --redact=100 --log-opts="--all --reflog --full-history" --max-decode-depth=2 .
python3 scripts/audit-public-source.py --report build/public-release-audit/git-inventory.json
python3 -m unittest discover -s scripts/tests -v
./gradlew -I scripts/dependency-inventory.init.gradle :app:publicDependencyInventory
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Audit reports stay under ignored `build/public-release-audit/`. The Git
inventory includes local-only and unreachable objects; binary/large files,
remote-only data, and arbitrary user folders are not certified by a text scan.
Only public Maven coordinates/version strings were sent to OSV; source and
private keys were not sent to an external scanning service.

The original 227 host tests pass with Bouncy Castle 1.86. Debug and unsigned
release APKs build for all four ABIs. The earlier 46 instrumentation tests
belong to alpha.6, **not** the changed crypto dependency. The working TV was
not installed, restarted, or contacted during this audit.

The history-free candidate was independently built with SDK/JDK environment
variables and no signing credentials: 227 host tests pass, and debug plus
unsigned-release APK assembly succeeds for all four ABIs without the seven
legacy spike files. All seven exporter tests also pass from that snapshot,
including actual Gitleaks rejection of the non-issued canary. No public APK
has been produced or published from this preparation; old assets are unchanged.

The exporter has rejection/cleanup tests. For its additional real-scanner
canary test, set `GITLEAKS_BIN` to the scanner executable before running unittest.
That test creates a non-issued token-shaped string in a temporary test repo,
requires export rejection, and never sends it to any service.

## Primary Guidance

- [GitHub: removing sensitive data](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)
- [GitHub: visibility changes](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility)
- [Gitleaks usage and limits](https://github.com/gitleaks/gitleaks)
- [GNU: corresponding source for downloaded binaries](https://www.gnu.org/licenses/gpl-faq.html#AnonFTPAndSendSources)
- [OSV advisory for the old BC ASN.1 implementation](https://osv.dev/vulnerability/GHSA-qp49-qgx5-5m26)
- [Pinned PlayFair source](https://github.com/FDH2/UxPlay/tree/462153392f2e30937424922039ff9f0cda5e7b1a/lib/playfair)
