# Verification

Tests below separate pure logic, native compilation, and actual device/client
behavior. The original rewrite checks were recorded on 2026-10-11; the current
device investigation is recorded separately below.

## Current Mac And TV Investigation

Updated 2026-10-16. The test receiver is a TCL/MediaTek Android 14 TV, not the
Sony Android 9 device named in the original backlog. The sender is macOS 26.5.

- Real pairing, FairPlay-derived stream decryption, H.264 configuration, IDR
  delivery, and hardware decoder output were observed. These are intermediate
  milestones, not proof of a visible desktop or audible audio.
- `ReceiverRuntimeTest.realBundledIdrProducesColoredSurfaceViewPixels` passed
  on the TV using `c2.mtk.avc.decoder` and the activity's actual SurfaceView.
  PixelCopy counted 10,924 colored pixels out of 14,400 sampled pixels.
- An explicitly requested, debug-only one-shot capture of the first decrypted
  Mac IDR decoded independently in ffmpeg as 1920x1080 H.264 High. Signalstats
  reported YMIN/YAVG/YMAX = 0 and U/V = 128: that captured source frame is black.
  This does not establish the content of every later frame. The capture stays
  in ignored `docs/spike_logs/private/2026-10-16/` and is not a public fixture.
- At 04:09:35 local time, Mac AirPlay logs reported
  `audioStream_createSupportedPCMFormatArray` and audio HAL device creation
  failing with `-12780` (`kCMBaseObjectError_ParamErr`). Control Center entered
  `waitingForCapabilities`, then logged `AVVideoController set output failed: 3`
  at 04:10:33. This is a concrete negotiation lead, not proof of its root cause.
- PCM alone and matching input/output format lists did not resolve that HAL
  failure. Adding the distinct, capability-gated feature bits 18 (PCM), 19
  (ALAC), and 21 (AAC-ELD) did: at 04:59:53 the Mac created the Screen audio HAL
  and enumerated 44.1 kHz stereo PCM without the earlier `-12780` failure.
  The sender then offered the sharing chooser and opened an AAC-ELD UDP stream.
  Format selectors and feature-bit positions are different namespaces.
- The native Apple ALAC source integration is bounded and produces byte-exact
  PCM for three independently decoded fixtures. Five JNI instrumentation tests
  passed on the TV, including malformed inputs and concurrent close/flush.
  This proves ALAC decoding, not that this Mac selected ALAC; it selected ELD.
- Host unit tests passed (150 tests) before the subsequent playback fixes.
  Debug/instrumentation APKs built and the capability/ALAC build was installed.
- The user confirmed audible audio while the picture remained black, but audio
  cut out approximately once per second. TV logs at 05:01 show nonzero decoded
  AAC-ELD PCM, successful AudioTrack writes, repeated `PCM queue overflow`,
  and `late PCM trimmed to playback clock` messages. Audible is not gap-free.
- The TV was turned off at the user's request. Further changes are being
  verified on an isolated API 36 ARM64 emulator, not silently tested on the TV.
- The exact jqssun Android UxPlay commit
  `c8defdd70d7e6a04f4f1b71d353653682d594106` built for ARMv7 and ARM64. Its
  original package started on the emulator, loaded `airplay_native`, bound
  port 7000, registered DNS-SD, selected AVC/HEVC decoders, and answered RTSP
  `/info` with a parseable binary plist. It was then stopped to avoid conflicts.
  No macOS media session has been verified against that baseline yet.
- `MirrorPlaybackIntegrationTest` reproduced a separate continuity bug on the
  emulator: the initial black IDR decoded, but an audio RTSP FLUSH reset the
  video decoder and all later dependent colored frames were lost. Removing
  that video reset made the identical test pass. The test exercises real RTSP,
  native key derivation with generated test vectors, continuous mirror CTR,
  AVC conversion, MediaCodec, and SurfaceView PixelCopy. It does not claim that
  a generated FairPlay test vector proves macOS pairing/interoperability.
- Audio playback now reserves PCM capacity before asynchronous decoder input,
  primes the track before starting, validates advancing hardware timestamps,
  and re-primes after underruns. The 160 ms PCM budget and 40 ms track target
  were not increased to hide the failure. Sustained device checks are recorded
  separately from pure queue/clock tests below.

Later on 2026-10-16, the user confirmed visible video and audible audio after the
startup-IDR preservation fix. See the verified baseline below. Gap-free extended
AAC-ELD playback, Firefox playback, correct Mac output restoration, and repeated
real-device reconnects remain separate acceptance gates.

## Playback Fix Verification, 2026-10-16

After the TV was turned off, the final combined changes passed:

- 173 Gradle host tests, including PCM reservations, partial writes, hardware
  clock validation/filtering, 44.1 kHz RTP bursts across wrap, shared A/V timing,
  discontinuities, and strict bounded deadlines.
- All 16 instrumentation tests on an isolated API 36 ARM64 emulator, including
  real AudioTrack PCM/AAC-LC playback, intentional underrun recovery, encrypted
  mirror TCP/FLUSH pixels, native ALAC JNI, 20 receiver restarts, occupied-port
  recovery, and both layout directions.
- A separate repeat of the three audio/encrypted-video media tests also passed.
- The encrypted black-to-color test counted 13,255 colored pixels out of 14,400
  after audio FLUSH, without a second IDR. The identical test failed before
  removing `video.flush()` from that RTSP handler.
- The AAC-LC run played 86,292 sample frames, accounted for a 1,772-frame
  startup trim and two decoder-resident packets (2,048 frames), with zero PCM
  overflow and no track reset. No EOS is supplied to this streaming decoder;
  bounded resident packets are not claimed as already audible samples.
- PCM burst/recovery played 260,716 frames, with zero PCM overflow and one
  re-prime associated with the deliberate gap. Bounded startup/calibration
  trimming remains distinct from a recurring continuity failure.
- Native ALAC host tests passed with Clang ASan/UBSan, including 12,000
  mutations. Debug/release/instrumentation APK assembly passed for all four
  configured ABIs. Debug APK signature and 16 KB ZIP alignment checks passed;
  ALAC attribution and the Apache license are included in its assets.
- macOS reported built-in speakers as both default output and system output
  after the TV was turned off. No routing preferences were changed for this
  check.

The clock fix preserves a legitimate burst's sample spacing instead of
rebasing each packet once its PTS exceeds arrival + 150 ms. Its hard future
window is the existing playout delay plus the existing 160 ms ingress/PCM
budget; no queue size was increased. Stream-local discontinuity correction
does not move the healthy stream's common NTP mapping.

The audio worker reserves capacity for decoder output before submission,
primes its track, ignores isolated empty-buffer dips for re-prime purposes,
and filters later hardware timestamp corrections once per observation. Logs
are bounded to initial clock samples and rate-limited drop diagnostics.

These tests do not establish gap-free AAC-ELD on the TV, moving macOS desktop
pixels, Firefox interoperability, or end-to-end latency. The TV was not updated
after it was turned off. The HEVC-capable reference remains a separately built
comparison APK, not a claimed feature of FossMirrorTV. The local release
variant build is unsigned and has not been published as an interoperability-
verified release.

## JNI Boundary Verification, 2026-10-16

The active native interfaces are `fairplay_jni.cpp` and
`audio_alac/alac_jni.cpp`. The original `receiver_jni.cpp` is legacy source,
not part of the current CMake target or APK. The hardening follows Android's
[JNI tips](https://developer.android.com/ndk/guides/jni-tips); reference
projects were studied, not assumed to have safe pointer/handle ownership.

- Both libraries register their methods in `JNI_OnLoad`. A linker version
  script exports only that entry point. ProGuard rules preserve the class and
  native method names used by registration.
- Kotlin owns worker threads and explicitly closes native resources. No
  `JNIEnv`, Java object reference, or raw array pointer is retained between
  calls. Bounded array-region copies avoid pinning the Java heap during work.
- Java longs are tagged, monotonically allocated registry IDs, not addresses.
  Each library permits at most 64 live sessions. A stale, forged, or foreign
  ID cannot dereference memory or close another library's resource.
- Operations serialize per session. Close waits for in-flight work and actual
  resource destruction without holding the registry-wide lock. Repeated and
  concurrent close are idempotent; IDs are never reused.
- C++ failures become checked Java exceptions at every native entry point.
  Pending Java exceptions are preserved, local references are scoped, and
  temporary FairPlay request/key buffers are wiped on success and failure.
  This is not a claim that Java-managed key arrays can be securely erased.
- Native input bounds are checked independently of the Kotlin API: FairPlay
  accepts only 16/164-byte setup requests and 72-byte encrypted keys; ALAC
  accepts 1..65536-byte packets with bounded PCM output.

Native host checks include concurrent use/close, registry capacity, allocation
failure, stale/foreign IDs, pending exceptions, and cleanup on failed array
allocation/copy. The JNI function-table fault injector tests error paths only;
real JNI execution is a separate Android runtime gate. ASan/UBSan cover the
new registry/support tests; the existing vendored FairPlay test uses ASan,
not a claim of UBSan-clean legacy cryptography.

```bash
cmake -S app/src/test/cpp -B /tmp/fossmirror-jni-tests -G Ninja \
  -DFOSSMIRROR_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_C_COMPILER=/opt/homebrew/opt/llvm/bin/clang \
  -DCMAKE_CXX_COMPILER=/opt/homebrew/opt/llvm/bin/clang++
cmake --build /tmp/fossmirror-jni-tests
ctest --test-dir /tmp/fossmirror-jni-tests --output-on-failure
```

A JDK is required for the host JNI headers. Set `JAVA_HOME` if CMake cannot
find it; the explicit Clang paths above refer to this host's working sanitizer
toolchain. The ordinary host script runs all three native test executables.

For real ART checks, enable CheckJNI before starting the isolated test process:

```bash
adb -s emulator-5580 shell setprop debug.checkjni 1
adb -s emulator-5580 shell am force-stop com.sal7one.tvtestproject
adb -s emulator-5580 shell am instrument -w -e class com.sal7one.tvtestproject.NativeBoundaryTest,com.sal7one.tvtestproject.NativeFairPlaySessionTest,com.sal7one.tvtestproject.NativeAlacDecoderTest com.sal7one.tvtestproject.test/androidx.test.runner.AndroidJUnitRunner
```

The tests deliberately bypass Kotlin guards to exercise the native checks.
They cover both libraries' lifetime limits, malformed input, cross-library
handles, and close races, including native decrypt/result-copy versus destroy.
Generated FairPlay test messages are explicitly not proof of a real macOS
handshake or a visible desktop. An independently validated FairPlay key
known-answer fixture remains a separate crypto/interoperability test gap.

Final JNI pass results on this host:

- 173 Gradle host tests passed; the standalone receiver-only script passed
  172 tests (it intentionally excludes the unrelated example test).
- All three native host suites passed with Clang 22 sanitizers. The handle
  stress suite also passed a separate ThreadSanitizer build with no reported
  data races. Registration tests cover missing VM/environment, failed class
  lookup, registration failure, pending exceptions, and class-reference cleanup.
- All 29 instrumentation tests passed on the isolated API 36 ARM64 emulator.
  ART logged `Late-enabling -Xcheck:jni`; the test process had no JNI errors,
  fatal exceptions, native crash, or unresolved native method.
- Debug and normal unsigned release builds passed for all four ABIs. The two
  JNI translation units compile with `-Wall -Wextra -Werror`. Each of the 16
  debug/release native libraries exports only `JNI_OnLoad`.
- A temporary R8-minified release, signed with the standard local debug key
  for emulator testing only, loaded both JNI libraries, bound RTSP, advertised
  DNS-SD, and returned a `/info` plist independently parsed by macOS `plutil`.
  Normal unminified release output and the debug emulator install were then
  restored; repository release settings and the user's release key were not
  changed or used for this check.
- Debug APK signature verification and debug/release 16 KB ZIP alignment
  checks passed. ARM64/x86_64 LOAD alignment and RELRO page-isolation checks
  passed according to Android's
  [16 KB checks](https://developer.android.com/guide/practices/page-sizes).
  The 32-bit libraries retain the NDK's default 4 KB segment alignment. These
  are build/package checks, not an execution test on a 16 KB device.

The test emulator and its temporary ADB forward were shut down afterward.
The powered-off TV was not contacted, updated, or used for these results.
JNI hardening does not establish visible Mac desktop pixels, uninterrupted
TV AAC-ELD audio, Firefox compatibility, or production readiness.

## Completed Checks

| Check | Result | What it does not prove |
| --- | --- | --- |
| Host JUnit protocol/media/transport suite | 130 tests pass | Android decoder/audio runtime or Mac compatibility |
| Gradle `testDebugUnitTest` | 136 tests pass, including receiver presentation and the existing example test | macOS interoperability |
| Native FairPlay framing/state/mode tests | Passing | A captured encrypted key decrypts to a known correct key |
| NDK ARM64 and ARMv7, API 28 | JNI/PlayFair compile and link pass | APK packaging or JNI execution on Android |
| Gradle debug/release/instrumentation APK assembly | Pass; native libraries packaged for all four ABIs | Runtime behavior on every ABI/device |
| Isolated emulator instrumentation | All 6 receiver/layout tests pass, including 20 restarts, occupied-port recovery, RTL layouts, and a decoded H.264 surface buffer | A decrypted macOS frame or audible audio |
| Signed release verification | `apksigner` and 16 KB `zipalign` checks pass | Sender compatibility |
| Signed release emulator launch | Installs, launches, binds RTSP 7000, and registers JmDNS without startup exceptions | LAN discovery outside emulator NAT |

Run the pure suite with:

```bash
bash scripts/verify-receiver-host.sh
```

The fallback script requires `kotlinc`, Java, CMake, SDK 36, and cached JUnit
4.13.2, Hamcrest 1.3, and Bouncy Castle 1.86. It prints its artifact directory.
It compiles the engine against the real Android SDK but does not package the
AndroidX/JmDNS service/activity or replace device tests with host assertions.

## Remaining Verification

The earlier sandbox-related Gradle/emulator blockers were resolved after full
local access was granted. Fresh Gradle builds and emulator tests now run.
The instrumentation fixture was corrected to take the parameter sets preceding
its first IDR instead of collecting repeated SPS/PPS entries from the whole clip.
The production parser's duplicate-ID validation was not weakened.

TV installation and a real Mac connection have now been exercised as described
above. Firefox playback, gap-free audio/routing checks, and a
visible first macOS desktop frame remain unverified. The release is an
experimental alpha, not a completed interoperability acceptance test.

## Emulator Runtime Gates

On a host where Gradle and the emulator can run:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am force-stop com.sal7one.tvtestproject
adb -s emulator-5580 shell am instrument -w -e class com.sal7one.tvtestproject.ReceiverRuntimeTest,com.sal7one.tvtestproject.ReceiverLayoutTest com.sal7one.tvtestproject.test/androidx.test.runner.AndroidJUnitRunner
```

Use an isolated emulator with no app activity/receiver service already running.
The tests bind RTSP directly and check 20 restarts, stable identity, auxiliary
control connections, rejected keyless SETUP, and a real H.264 decoded buffer.
A surface-buffer callback proves decoding, not that the image is a Mac desktop.
All three tests were executed successfully on a fresh isolated emulator using
software rendering and a separate ADB server. The signed release APK was then
installed and launched on that same emulator. That original emulator run did
not access a user TV; later TV checks are recorded separately above.

For the encrypted mirror and real AudioTrack regressions, additionally run:

```bash
adb -s emulator-5580 shell am instrument -w -e class com.sal7one.tvtestproject.MirrorPlaybackIntegrationTest,com.sal7one.tvtestproject.AudioPlaybackRuntimeTest com.sal7one.tvtestproject.test/androidx.test.runner.AndroidJUnitRunner
```

The mirror fixture has a black intro and colored dependent frames with no
second IDR. Audio tests use generated tones and do not request microphone or
screen-capture permissions. These checks cannot certify TV hardware playback.

## Signed Release

```bash
bash scripts/build-release.sh --no-daemon
```

This builds the release, runs Gradle unit tests, verifies the APK signature and
alignment, and writes the APK/checksum under ignored `releases/`. Signing keys
remain outside Git in `~/.android/fossmirrortv-release/` by default. Keep a secure
backup; replacing the key prevents compatible updates to installed releases.
The first alpha's new key is not compatible with an older debug-signed install.
Alpha.3 retains the previous alpha signing key and increments the version code to 4.

Alpha.5 packaging, 2026-10-16: `bash scripts/build-release.sh --offline --no-daemon`
passed signed release assembly and all 222 host tests (zero failures/errors/skips).
`apksigner verify` and `zipalign -c -P 16 4` passed. The certificate SHA-256
matches the released alpha.3 certificate. `aapt dump badging` confirms version
`1.1.0-alpha.5`, version code 6, minSdk 28, and all four native ABIs. APK and
checksum remain under ignored `releases/`; private signing material is not
committed or uploaded. This packaging pass does not add new TV or Mac runtime
evidence, and does not replace the working TV installation.

The signed alpha.5 APK also installed and reached Ready on an isolated API 36
ARM64 emulator. Its actual 1920x1080 home screenshot and the earlier real-decoder
control-test screenshot are included in the README. No private Mac desktop
capture is published. The temporary screenshot emulator and port forward were
removed afterward; no new macOS media session was tested in this packaging pass.

## UI And Decoder Responsiveness

The TV screen and compact landscape layout are checked using emulator screenshots
and actual Start/Stop/Restart controls. Native runtime tests still exercise a
decoded surface buffer, not a fake streaming screen. Generated source artwork,
final prompts, and the compression script are documented under `design/brand/`.

Alpha.3 regression coverage includes Arabic/RTL and English layouts at 1920x1080,
960x540, and 720x480, including the long EADDRINUSE error. Controls stay physically
left and remain outside the scrolling error details. A real shell listener was
also bound to emulator TCP 7000: the service selected TCP 46721, registered JmDNS
on 10.0.2.16:46721, and displayed that actual endpoint while remaining ready.
The shell listener was not closed or replaced by the app. LAN addresses come from
Wi-Fi/Ethernet interfaces, not just completed DNS-SD registrations.

The Android 11+ optional decoder setting follows
[MediaFormat.KEY_LOW_LATENCY](https://developer.android.com/reference/android/media/MediaFormat#KEY_LOW_LATENCY)
and [CodecCapabilities.FEATURE_LowLatency](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_LowLatency).
It is requested only for a codec advertising support, and configuration retries
without it if necessary. API 28 retains the normal decoder path. Emulator logs
record the actual selected mode; no end-to-end latency reduction is claimed.

## Alpha.6 Native AVC And Receiver Names

Updated 2026-10-16. Each installation now has a stable DNS-SD name formed from
its device category and existing random identity suffix, for example
`FossMirrorTV - TV A1B2C3`. The same name is used by AirPlay, RAOP, `/info`,
notifications, reconnect dialogs, and the label below the app title. The
identity preferences are excluded from legacy backup, cloud backup, and
device transfer. These are installation identities, not hardware MACs.

Verification includes 227 host tests and all 46 instrumentation tests on an
isolated API 36 ARM64 AOSP ATD emulator with CheckJNI enabled. Two isolated
installation contexts start actual RTSP engines concurrently, expose different
ports/keys/identities/names, return matching `/info` names, and construct distinct
AirPlay/RAOP records. Restart keeps the name; UI labels match it. This is not a
claim that two physical devices were simultaneously discovered in the Mac menu.
Both devices must install the update and share a multicast-capable LAN.

The AVC native API is stateless, uses RegisterNatives, exports only JNI_OnLoad,
and never returns pointers or keeps JVM references between calls. Input/output
are bounded to 8 MiB and 4096 NALs. JNI copies are explicit; no critical array
pin is held while parsing. Temporary native frame buffers are wiped. One parsed
frame is shared with the decoder; its Java Annex-B bytes remain valid after
the decrypted wire buffer is wiped. SPS/PPS changes keep the tested managed
configuration parser. FairPlay, pairing, AES, ALAC, hardware codec selection,
shared A/V scheduling, and queue limits are not replaced by this change.

All four native host suites pass. The AVC suite includes bounds, overlap,
truncation, malformed slices, and 30000 mutations under ASan/UBSan; existing
JNI ownership/error suites also run. The legacy PlayFair suite remains
ASan-only. Actual ART tests compare the JNI parser to the reference on 3000
mutations, malformed JNI metadata, and concurrent calls, then exercise encrypted
video pixels, startup burst preservation, audio, and English/Arabic layouts.

The native target is optimized with -O2 even in debug APKs. Without optimization,
the first 64 KiB microbenchmark took 1238535 ns per JNI parse, versus 79795 ns for
the former double managed parse. Optimized samples varied: old/native pairs
were 140832/76537 ns, 54565/57202 ns, and 87951/64422 ns. A third single-managed
sample was 114029 ns; this benchmark's ordering/JIT/GC variation is too large
to claim language-wide or end-to-end superiority. No speed assertion is used.
The defensible improvement is eliminating the duplicate conversion and one
large managed output allocation per frame, not a claimed glass-to-glass gain.

Final debug, release, and instrumentation APK assembly passed for ARMv7,
ARM64, x86, and x86_64. The release APK uses the existing release certificate;
signature verification and 16 KB ZIP alignment passed. The new AVC library
exports only JNI_OnLoad on all four ABIs, and its 64-bit LOAD segments have
16 KB alignment. These are package checks, not execution on a 16 KB device.

The TV was only read for metadata diagnostics, not reinstalled or interrupted.
Its live logs showed approximately 30 fps, queue depths 1..2, about 2 ms average
queue wait, and no late video outputs. Audio also recorded occasional trims
and an underrun. A brief slowdown that later recovered does not establish
whether Wi-Fi, sender load, or another cause was responsible.

## Playback Modes And Controls

The next-connection playback modes keep one shared A/V timeline:

| Mode | Receiver playout cushion | Maximum offered video rate |
| --- | --- | --- |
| Responsive | 100 ms | 30 fps |
| Balanced (default, working baseline) | 120 ms | 30 fps |
| Smooth | 150 ms | 60 fps only with exact hardware AVC High/1920x1088/60 capability evidence; otherwise 30 fps |

These values are receiver scheduling budgets, not measured Mac-to-TV latency.
Changing the selection does not tear down the current session. `/info` and the
RECORD audio-latency response use the selected delay; A/V do not get independent
offsets. Sender frame rate and real-time throughput still require a live check.

Fit/Fill transforms only the displayed SurfaceView; it does not resize or replace
its producer buffers, discard the reference chain, or restart MediaCodec. The
remote-accessible controls separate client disconnect from a complete receiver
restart. Client disconnect keeps the same RTSP listener, key identity, port, and
Bonjour registration; the sender must select the receiver again. No remote
pause/seek functionality is claimed.

On a fresh phone-style emulator, acknowledge Android's first-use fullscreen
tutorial before injecting D-pad keys. Otherwise `ImmersiveModeConfirmation`, not
the app's dialog, owns window focus. For the disposable emulator only:

```bash
adb -s emulator-5580 shell settings put secure immersive_mode_confirmations confirmed
```

Five-second `video_timing` windows record queue depth/wait and output lateness,
without retaining frame bytes. `connection_first_video_ms` is measured from the
accepted control connection to the first render attempt, not visible-pixel time.
Codec metadata caches avoid repeat discovery allocations, but do not cache
codec instances or assert resource availability. Audio diagnostics separately
count source PCM and bounded gap-fill silence so playback accounting is exact.

### HEVC/4K Work In Progress

`H265Config` now parses bounded hvcC/hvc1 data, VPS/SPS/PPS, coded/cropped
dimensions, Main/Main10 profiles, and complete length-prefixed access units.
Tests use independently generated libx265 parameter sets, including 4K Main10,
plus truncation and malformed-input checks. IDR and CRA are distinct.
`VideoCapabilities` queries exact Android format/profile/size/rate support.

This is not a live HEVC mirror implementation. The active receiver remains AVC;
feature bit 42 stays off and `/info` still offers 1920x1080. Before enabling HEVC,
validate real Mac codec-data captures, add HEVC MediaCodec dispatch, handle CRA
and associated RASL recovery, verify colored decoded pixels and sustained audio,
then gate 4K negotiation on actual hardware decode and display capabilities.
The [pinned UxPlay framing reference](https://github.com/FDH2/UxPlay/blob/462153392f2e30937424922039ff9f0cda5e7b1a/lib/raop_rtp_mirror.c#L625)
and [jqssun feature gate](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/src/main/cpp/native_bridge.cpp#L290)
are behavioral evidence, not a substitute for those runtime gates.

### Preview Verification: 2026-10-16

- Gradle debug and instrumentation builds succeeded for all four native ABIs;
  all 222 host unit tests passed.
- All 39 instrumentation tests passed on the isolated API 36 ARM64 emulator
  in 60.95 seconds, after acknowledging its first-use fullscreen tutorial.
  After the final reconnect icon/label spacing adjustment, all seven control,
  layout, and real-decoder sizing checks passed again on a fresh emulator.
- Each playback mode exercised real PCM and AAC-LC decoder/AudioTrack output
  with bounded queues, no continuous-input track resets, and exact accounting
  for source PCM, startup trimming, decoder residency, and explicit silence.
- Twenty client disconnects retained the same control endpoint/public identity.
  An 80-connection reset burst did not terminate the listener. The negotiated
  `/info` latency and maximum frame-rate gates matched the selected mode.
- Real colored dependent frames continued after Fit/Fill changes without a
  second IDR or a Surface replacement. Remote selection/persistence,
  confirmation guards, English/Arabic layouts and compact viewports passed.
  The emulator fixture screenshot was inspected locally; it is not a Mac capture.
- The TV was not reinstalled or restarted during this preview work. It retains
  the user-confirmed baseline. Live latency/FPS measurement, extended AAC-ELD
  playback, physical-TV control checks, and the deliberately stalled decoder
  timeout case remain runtime follow-ups, not silently claimed as passing.

## macOS Acceptance Gates

### Verified Working Baseline: 2026-10-16

The user confirmed visible macOS video and audible audio on the TCL MT9689
Android 14 TV. The startup failure was receiver-side: the bounded video queue
discarded the only IDR while the hardware decoder was still configuring. The
queue now backpressures the TCP producer instead of clearing the reference
chain. Audio refill and timestamp polling were also corrected.

Verification: 178 host unit tests passed; 30 instrumentation tests passed on the
API 36 emulator; the two mirror playback and two audio runtime tests passed on
the TV. The startup-burst fixture fails with the old queue and passes with the
fix, including actual colored surface pixels on the TV.

The installed debug APK is preserved locally at
`releases/baseline-alpha4/FossMirrorTV-working-debug.apk` (also copied under
`/private/tmp/fossmirrortv-working-av-20261016.apk`), SHA-256
`0237fbb0d7ebb3a3fa7ba094a57641b4e022b67f7fbc6a3e55162ed772a41c12`.
This is a working baseline, not proof of optimal latency or uninterrupted
long-running AAC-ELD playback. Live logs still showed occasional late audio trims
and an underrun. Private desktop captures and downloaded media are not committed.

An emulator alone does not prove mDNS visibility or macOS interoperability.
Standard emulator NAT may need separate networking configuration; do not call
an unreachable receiver a protocol failure.

1. Confirm `_airplay._tcp` and `_raop._tcp` discovery matches `/info` and the
   actual bound ports/public key.
2. Capture real pairing/FairPlay exchanges and validate a known decrypted key
   against an independent reference. Never publish the key or private identity.
3. Confirm key/timing SETUP precedes stream SETUP and TCP 7100 is listening
   before its response succeeds.
4. Observe `first_sps_seen`, `first_pps_seen`, `first_idr_seen`, and
   `first_frame_queued`, then verify visible macOS desktop pixels.
5. Play non-DRM video/audio in Firefox and another application. Confirm audible
   receiver output, bounded delay, and restored Mac output after disconnect.
   Record macOS version, output device, and failure details; do not infer that
   a receiver-side code change fixed a sender-side browser/audio issue.
6. Repeat connection/disconnection and surface recreation 20 times. Confirm no
   leaked sockets/threads, stale audio focus, growing queue, or stuck ports.
7. Check Wi-Fi/Ethernet loss/recovery and foreground-service Stop/Restart.

Collect metadata logs with:

```bash
adb -s emulator-5580 logcat -v threadtime FossAirPlay:I FossMirrorTV-Video:I AirPlayAudio:I FossReceiver:I AndroidRuntime:E '*:S'
```

Render-attempt and queue logs are diagnostics. Neither is a substitute for
visible pixels and audible playback. See the [capture guide](spike_logs/README.md).
