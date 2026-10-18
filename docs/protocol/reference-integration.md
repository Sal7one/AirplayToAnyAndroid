# AirPlay Reference Integration

Historical investigation notes from 2026-10-16. The intermediate black-screen
and audio failures below were followed by visible Mac video and audible audio;
see the later baseline in [Verification](../TESTING.md). Reference review is not
a claim of full upstream compatibility or a current unresolved-bug list.

Reviewed 2026-10-16. Combine useful components and independently verified
behavior from these projects, not four competing receiver implementations.
Keep one session owner, one negotiated capability model, and one media clock.
The first acceptance gate is a visible macOS desktop with working audio and
clean disconnect, not additional advertised features.

## Source Checkouts

These are shallow, sparse source clones outside this application's Git tree.
Bundled APKs, large release binaries, and recursive dependency checkouts are
not imported. None of the reference applications was executed during this
review. Their README claims are not device-test results for FossMirrorTV.

| Project | Pinned revision | Local checkout |
| --- | --- | --- |
| [xenos1337/AirPlayServer](https://github.com/xenos1337/AirPlayServer/tree/aa9ce92455ac249076d9309bd435985df9cc37e8) | `aa9ce92455ac249076d9309bd435985df9cc37e8` | `/private/tmp/fossmirror-reference-xenos-20261016` |
| [prat3ik/iMirror](https://github.com/prat3ik/iMirror/tree/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00) | `9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00` | `/private/tmp/fossmirror-reference-imirror-20261016` |
| [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server/tree/c8defdd70d7e6a04f4f1b71d353653682d594106) | `c8defdd70d7e6a04f4f1b71d353653682d594106` | `/private/tmp/fossmirror-reference-jqssun-20261016` |
| [mazer666/PhairPlay](https://github.com/mazer666/PhairPlay/tree/ccfe04f391a59496405f1cead6909ac1cde380dd) | `ccfe04f391a59496405f1cead6909ac1cde380dd` | `/private/tmp/fossmirror-reference-phairplay-20261016` |

The [F-Droid package](https://f-droid.org/en/packages/io.github.jqssun.airplay/)
points to jqssun's repository. iMirror identifies PhairPlay as its upstream;
agreement between the two is therefore not independent protocol evidence.
The temporary checkouts may be cleaned by the OS; the pinned revisions are the
durable identifiers for future comparisons.

## Immediate FossMirrorTV Evidence

The actual TV SurfaceView rendered a colored H.264 fixture through its hardware
decoder. A separately captured, decrypted first Mac IDR decoded in ffmpeg as
all black. Mac logs also recorded supported-PCM-format and audio HAL creation
failures (`-12780`), followed by a display capability wait and output failure.
See [the test record](../TESTING.md#current-mac-and-tv-investigation).

This supports testing capability negotiation before replacing the renderer.
It does not prove the PCM omission caused every black frame.

The installed correction advertises capability-tested PCM 44.1 kHz,
16-bit stereo (`0x800`), real ALAC (`0x40000`), and AAC-ELD (`0x01000000`).
It validates the same selectors in SETUP. Distinct feature bits 18/19/21 must
also identify the available PCM/ALAC/ELD paths; adding PCM format selectors
alone did not fix the Mac HAL error. The feature-bit correction did produce a
Screen HAL and sharing chooser. The user now hears audio, but reports regular
gaps and a black picture. Those remaining failures are not considered solved.
The bit definitions are corroborated by the
[OpenAirPlay implementation](https://github.com/openairplay/airplay2-receiver/blob/master/ap2/connections/audio.py#L294-L318),
not an Apple-published specification. Broad upstream format masks are not a
reason to advertise formats this receiver cannot decode.

Do not add a speculative PCM byte swap: UxPlay describes its ct=1 input as
[S16LE](https://github.com/FDH2/UxPlay/blob/master/renderers/audio_renderer.c#L26-L62).
That path is disabled by default there and actual Mac PCM playback still needs
a captured sample. Its outgoing RTP L16 conversion is a different path.

## Project Decisions

| Reference | Best use here | Do not import unchanged |
| --- | --- | --- |
| jqssun | Android UxPlay/JNI port blueprint, persistent surface ownership, audio output diagnostics | Capability masks, log-only flush callbacks, full backend without comparative tests |
| PhairPlay | Kotlin protocol cross-checks, ALAC configuration helper, lifecycle test ideas | Receiver-wide PIN authorization, insufficient FairPlay guards, type-103 drain-only path |
| iMirror | PhairPlay fork deltas, decoder hints to benchmark, ALAC wrapper example | Broad audio masks, unguarded late NSD callbacks, claims of solved Mac audio |
| Xenos | Reconnect/teardown test cases, queue recovery and pacing ideas | Windows renderer, empty audio flush, clock replacement, end-to-end latency claims |

### Android AirPlay Server

jqssun is the strongest Android port reference among these four, not a proven
drop-in replacement. Its [CMake source list](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/src/main/cpp/CMakeLists.txt#L94-L160)
really compiles UxPlay protocol, pairing, timing, and media transport code.
[JNI callbacks](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/src/main/cpp/android_raop_callbacks.c#L105-L126)
send video to Kotlin while audio enters the native engine. A migration would
replace much of `AirPlayServer.kt` and transport code, not merely our FairPlay
JNI. Retain `ReceiverContracts.kt` and explicitly adapt timestamp units, frame
ownership, failures, and teardown. This is high-cost work, not the next small
black-screen fix.

Two useful, separable design candidates:

- A [persistent codec SurfaceTexture and EGL display attachment](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/src/main/kotlin/io/github/jqssun/airplay/renderer/VideoPipeline.kt#L111-L145)
  could avoid waiting for another IDR after our activity surface is recreated.
  Evaluate it in `VideoPipeline.kt` with last-frame and detach/reattach tests.
  Account for the extra GPU stage; retain independent decoder output draining.
- [Oboe output recovery and buffer diagnostics](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/src/main/cpp/audio_output.h#L14-L159)
  are useful for `AudioPipeline.kt`. Keep our shared `SessionClock`; the
  reference's [adaptive cushion can reach one second](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/src/main/cpp/timeline_buffer.h#L147-L153).
  A low-latency output flag alone does not bound total playout delay.

The [audio decoder factory](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/src/main/cpp/audio_decoder.h#L380-L444)
implements AAC and software ALAC, not every format in the inherited broad
advertisement. Its [audio/video flush callbacks only log](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/src/main/cpp/android_raop_callbacks.c#L221-L230).
The pinned tree's release CI assembles APKs; it does not establish real-device
media correctness. Keep our flush semantics, decoder-output validation, and
pixel tests rather than replacing them merely because the app is on F-Droid.

The source depends on a patch series. Carry applicable sender/volume regression
cases, but do not copy the [build task that checks out upstream files before patching](https://github.com/jqssun/android-airplay-server/blob/c8defdd70d7e6a04f4f1b71d353653682d594106/app/build.gradle.kts#L92-L109)
into a developer's mutable checkout. The reviewed gitlinks are:

| Dependency | Revision |
| --- | --- |
| UxPlay | `462153392f2e30937424922039ff9f0cda5e7b1a` |
| FFmpeg | `38b88335f99e76ed89ff3c93f877fdefce736c13` |
| libplist | `f41b1ea67045e0c09339974d83e389972d84f166` |
| openssl-cmake | `4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8` |

### PhairPlay

Use selected Kotlin serialization/crypto behavior as cross-checks. Its
[mirror key derivation](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorCrypto.kt#L30-L37)
and [decrypt-before-drop ordering](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/handshake/MirrorStreamServer.kt#L103-L128)
are useful regression references for `SessionCrypto.kt` and `MirrorReceiver.kt`.
The [ALAC cookie helper](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/handshake/AlacDecoder.kt#L86-L99)
is a narrow reuse candidate once actual ALAC decoding is added.

Do not adopt its receiver-wide authorization or native validation boundary:
[PIN state survives all control connections](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt#L224-L227),
and the [pair-verify gate](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt#L510-L520)
consults that shared state. Its [phase-two FairPlay entry](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/handshake/FairPlay.kt#L51-L68)
does not establish handshake order or validate the mode later used by native
PlayFair. Retain our per-session verified-secret access and native mode/order
checks. Any PIN implementation must bind authorization to the verified peer,
not just remember that somebody paired once.

Its [type-103 buffered audio receiver](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/handshake/BufferedAudioServer.kt#L45-L63)
reads/logs bytes without playback, and [FLUSH/PAUSE](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/RtspHandler.kt#L898-L906)
return success without flushing or pausing media. Its README also distinguishes
Cast/Miracast control-plane work from media playback. These paths violate our
no-fake-success requirement if imported as supported features.

The [startup sequence](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/kotlin/com/phairplay/airplay/AirPlayReceiver.kt#L149-L155)
advertises before asynchronous RTSP binding. Keep FossMirrorTV's actual bound
port and readiness ordering, particularly with the TV's built-in receiver
already occupying port 7000.

### iMirror

Use as a focused PhairPlay fork comparison, not a second independent protocol
reference. Its [decoder hints](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/app/src/main/kotlin/dev/imirror/receiver/airplay/VideoDecoder.kt#L108-L142)
request low latency, realtime priority, and a 60 fps operating rate. Benchmark
optional hints in `receiver/media/VideoPipeline.kt`; retain our capability
checks and configuration retry without unsupported options.

The [software ALAC wrapper](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/app/src/main/cpp/alac_jni.cpp#L59-L101)
is a useful integration example for `AudioPipeline.kt` and `ReceiverContracts.kt`.
Its [tests](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/app/src/test/kotlin/dev/imirror/receiver/airplay/handshake/AlacDecoderTest.kt#L7-L47)
validate configuration cookies, not actual decoded samples. Require bounded
native inputs and real decode fixtures before enabling this additional codec.

Do not copy these behaviors unchanged:

- [Broad audio masks](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/app/src/main/kotlin/dev/imirror/receiver/airplay/handshake/InfoResponder.kt#L39-L49)
  advertise many formats while the [decoder dispatch](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/app/src/main/kotlin/dev/imirror/receiver/airplay/handshake/AudioStreamServer.kt#L352-L374)
  sends non-ALAC/non-AAC-LC values into the ELD path. Preserve explicit rejection
  of unsupported formats.
- [Serialized NSD registration](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/app/src/main/kotlin/dev/imirror/receiver/airplay/MdnsService.kt#L237-L247)
  can start AirPlay from a delayed RAOP callback after `stop()`. Any adaptation
  needs a lifecycle generation guard. Our JmDNS service does not need this
  NsdManager-specific change.
- The [Mac troubleshooting advice](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/README.md#L99-L102)
  recommends disabling mirror audio for disconnects. This is not evidence of
  solved Mac audio interoperability.

### Xenos AirPlayServer

Use its [smoke-test scenarios](https://github.com/xenos1337/AirPlayServer/blob/aa9ce92455ac249076d9309bd435985df9cc37e8/tests/AirPlayLibSmokeTests/AirPlayLibSmokeTests.c#L120-L207)
to extend `MirrorReceiverTest.kt` and runtime tests: partial-header disconnect,
pause/resume, repeated stop, and reconnect. Its [mirror stop guard](https://github.com/xenos1337/AirPlayServer/blob/aa9ce92455ac249076d9309bd435985df9cc37e8/AirPlayServerLib/lib/raop_rtp_mirror.c#L903-L969)
is another ownership reference. Reconnect adaptation must define session and
CTR cipher continuity, not merely retain a listening socket.

The [headless adapter](https://github.com/xenos1337/AirPlayServer/blob/aa9ce92455ac249076d9309bd435985df9cc37e8/MirrorSimAdapter/MirrorSimAdapter.cpp#L994-L1079)
uses bounded queues and IDR recovery. Keep parameter sets outside the discardable
frame queue: its overflow clear can discard codec data while its deduplication
cache still suppresses resending it. Do not inherit a 120-frame/64 MiB limit as
an Android latency target.

Reject wholesale playback/clock replacement. Its [drift controller](https://github.com/xenos1337/AirPlayServer/blob/aa9ce92455ac249076d9309bd435985df9cc37e8/AirPlayServer/CSDLPlayer.cpp#L1969-L2006)
runs only in the nominal sample-rate conversion path, so equal nominal rates
do not receive that correction. Its [audio flush callback](https://github.com/xenos1337/AirPlayServer/blob/aa9ce92455ac249076d9309bd435985df9cc37e8/airplay2dll/src/FgAirplayServer.cpp#L494-L500)
is empty. Keep our shared `SessionClock` and explicit audio flush behavior.
The [reported latency](https://github.com/xenos1337/AirPlayServer/blob/aa9ce92455ac249076d9309bd435985df9cc37e8/AirPlayServer/CSDLPlayer.cpp#L1832-L1842)
starts after decoding and is not end-to-end mirroring latency. SDL/Windows
rendering is not an Android drop-in.

## Licensing And Provenance

Root license labels do not cover every bundled component. Before copying any
implementation, record upstream commit/file, retained copyright/license text,
local modifications, dependency versions, and the distribution obligations of
the resulting build. Keep this alongside [crypto-source.md](crypto-source.md).
The following is a source inventory, not legal clearance:

- **jqssun:** the app is listed as GPL-3.0-only by F-Droid. Do not silently label
  copied files "or later." Inventory UxPlay/PlayFair, FFmpeg/libplist, Oboe,
  OpenSSL, openssl-cmake, and llhttp separately; static libraries require a
  distribution review, including applicable source/relink requirements.
- **PhairPlay:** root Apache-2.0 does not relicense imported code. Its
  [CMake file identifies PlayFair from RPiPlay](https://github.com/mazer666/PhairPlay/blob/ccfe04f391a59496405f1cead6909ac1cde380dd/app/src/main/cpp/CMakeLists.txt#L4-L11),
  whose [component license is GPLv3](https://github.com/FD-/RPiPlay/blob/64d0341ed3bef098c940c9ed0675948870a271f9/lib/playfair/LICENSE.md#L2-L3).
  The pinned PhairPlay tree lacks that component license file. Apple ALAC has
  separate Apache-2.0 source headers.
- **iMirror:** [NOTICE.md](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/NOTICE.md#L6-L57)
  declares GPLv3 overall and identifies PlayFair, Apple ALAC, and PhairPlay
  lineage. Preserve those notices and resolve the acknowledged provenance gaps.
- **Xenos:** root MIT covers repository-authored code, while its
  [third-party inventory](https://github.com/xenos1337/AirPlayServer/blob/aa9ce92455ac249076d9309bd435985df9cc37e8/THIRD_PARTY_NOTICES.md#L6-L47)
  identifies GPL PlayFair and LGPL FFmpeg/libplist. The native stack is not
  MIT-only.

## Integration Order

1. **Fix media continuity after successful negotiation.** The same Mac created
   its HAL and sent audible ELD audio after the feature-bit correction. Logs
   show PCM overflows/late trimming, while the user still sees black video.
   Exercise queue reservations, playback-clock recovery, and audio-only FLUSH
   without resetting video references. Do not widen capability masks blindly.
2. **Run a reference receiver as an independent comparison.** Use the same Mac,
   TV, network, resolution, and non-DRM content, with only one advertised test
   receiver active. Record protocol metadata and actual output. Do not infer
   interoperability from a repository description or successful APK build.
3. **Choose the protocol implementation deliberately.** Evaluate jqssun's
   Android UxPlay boundary as a replacement behind `AirPlayServer`, not as a
   second listener alongside it. Keep the existing implementation until the
   candidate passes the same handshake, media, teardown, and malformed-input
   tests. Preserve JNI/session ownership and attributed source revisions.
4. **Keep imported audio paths bounded.** Apple ALAC is now integrated with
   byte-exact fixture/JNI tests; its provenance and retained Apache notice are
   in `app/src/main/cpp/audio_alac/README.md`. Verify sustained playout, focus
   release, and Mac route restoration. Retain one clock shared with video.
5. **Improve video recovery and pacing.** Apply measured queue limits and
   keyframe recovery before adding a new GPU stage or vendor codec knobs.
   Preserve CTR cipher continuity even while discarding decoded media.
6. **Improve receiver ergonomics after interoperability.** Keep status/errors
   visible, ensure remote-focus navigation, recover after Home/network changes,
   and keep diagnostics local and opt-in. Do not add Cast, Miracast, HEVC, HLS,
   or buffered-audio claims while their playback paths are absent.

## Product Acceptance Gates

- A non-black moving Mac desktop appears on the TV, not only a queued IDR.
- Firefox and another non-DRM player produce audible audio without disrupting
  normal Mac output after disconnect.
- Twenty connect/disconnect and Home/return cycles complete without retained
  sockets, stale audio focus, stranded threads, or increasing memory use.
- Loss/reconnect and sustained audio drift recover within bounded queues.
- Latency is measured end to end on the same hardware/network. A decode-to-
  presentation statistic is not glass-to-glass latency.
- Unsupported modes are rejected explicitly, and all imported components have
  pinned provenance, retained notices, and a reviewed distribution plan.

## Verification Scope

The capability/ALAC integration passed 150 host unit tests and APK assembly;
five native ALAC instrumentation tests passed on the TV. The updated APK was
installed and a real Mac connection resolved the HAL error but still had black
video and intermittent audio. Native Apple ALAC source was imported with bounds
checks and notices; the reference report alone was not counted as integration.

The exact jqssun baseline also built from source for ARMv7/ARM64 and ran under
its original package on the emulator. Native startup, AVC/HEVC selection,
DNS-SD registration and RTSP `/info` were observed. It has not passed a Mac
media test and has not replaced FossMirrorTV's backend yet.
