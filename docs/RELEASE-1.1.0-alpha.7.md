# FossMirrorTV 1.1.0-alpha.7

First public binary release from the clean-history repository. This is an
**experimental prerelease for trusted LANs**, not a stable or Play Store release.
Android 9+; application ID `com.sal7one.tvtestproject`; version code `8`.

## Downloads

| APK suffix | Choose this for |
| --- | --- |
| `universal` | Recommended if unsure; contains all four architectures. |
| `arm64-v8a` | 64-bit ARM phones and TVs. |
| `armeabi-v7a` | 32-bit ARM devices, including many older Android TVs. |
| `x86_64` | 64-bit Intel/AMD Android devices and emulators. |
| `x86` | 32-bit Intel Android devices and emulators. |

All APKs are standalone, release-signed, and non-debuggable. Install **one** APK,
not all five. They use the existing FossMirrorTV release certificate and a higher
version code than alpha.6. A debug-signed installation cannot update in place;
uninstalling it resets app settings and receiver identity.

The release also includes:

- `FossMirrorTV-1.1.0-alpha.7-source.zip`: corresponding source from the tagged commit, with build scripts and component licenses.
- `release-manifest.json`: exact source revision, public signing-certificate fingerprint, APK architectures, sizes, and hashes.
- `SHA256SUMS`: SHA-256 hashes of the five APKs, source ZIP, and manifest. Compare `shasum -a 256 <downloaded-file>` with its matching line.

## What Changed

- Public GPL-3.0-only source with component notices, privacy/security notes, and fresh Git history.
- Bouncy Castle 1.86 replaces the older private alpha.6 dependency.
- Universal and per-ABI release packaging, verified before publishing.
- Corresponding-source archive and checksums travel with the APKs.
- Existing H.264 video, capability-gated PCM/ALAC/AAC-ELD audio, Fit/Fill, playback modes, and distinct TV/phone receiver names are retained.

## Verification And Limits

- 227 JVM host tests pass.
- Four native host suites and the ALAC fixture/mutation suite pass without sanitizers.
- Eleven Python packaging/export tests pass, including a real secret-scanner rejection test.
- Debug, Android-test, and release APK assembly passes for all four ABIs.
- Published APKs pass signature verification, non-debuggable/version/ABI checks, and 16 KB ZIP alignment. These checks do not prove execution on a 16 KB Android device.
- Fresh Android instrumentation was **not run**: the disposable emulator could not boot because the host lacked disk space. The existing emulator and TV were not modified.
- The current host's AddressSanitizer runtime deadlocked during initialization before test `main`; those runs were stopped. Plain native tests pass, but this is not a new sanitizer pass. Earlier sanitizer evidence remains historical.
- Real Mac video/audio worked on one TCL/MediaTek TV on an earlier build. The 46 prior Android instrumentation checks belong to alpha.6, not this crypto dependency update. Fresh TV/Mac, Firefox, extended audio continuity, and broad device compatibility remain unverified.

No PIN or trusted-device access control is implemented. Never expose the receiver
to the Internet or use an untrusted LAN. Live HEVC/4K is not enabled. See
[Security](../SECURITY.md), [Privacy](../PRIVACY.md), and [test history](TESTING.md).

## Build This Version

Use JDK 17+, Android SDK 36, the NDK, CMake 3.22.1, and Python 3.9+:

```sh
git clone --branch v1.1.0-alpha.7 https://github.com/Sal7one/AirplayToAnyAndroid.git
cd AirplayToAnyAndroid
./gradlew :app:testDebugUnitTest
python3 -m unittest discover -s scripts/tests -v
bash scripts/build-release.sh
```

The signing script creates or reuses **your own** key outside Git. A self-built
APK therefore does not update an official signed APK unless you own its signing
key. No signing credentials are in the repository or source ZIP. Build outputs
need not be byte-for-byte identical across SDK/toolchain environments.
