# FossMirrorTV — Revised Implementation Backlog (v2)

**Project**: FOSS AirPlay receiver for Android TV  
**Repo**: `foss-mirror-tv`  
**License**: GPL-3.0-or-later  
**Protocol base**: UxPlay (FDH2/UxPlay, GPLv3) — single lineage, no APSDK mixing  
**Target device**: Sony KD-55X8000E (Android 9, API 28) and similar Android TV hardware  
**Primary goal for v1.0**: macOS Screen Mirroring → Sony Android TV, stable 1080p/30fps

---

## License stance (fixed from v1)

Path A only. UxPlay/RPiPlay are GPLv3. This project is GPL-3.0-or-later.  
APSDK (`air-display/apsdk-public`) is GPL-2.0 with a private FairPlay submodule that
ships as an empty stub — it explicitly does not work without private Apple components.  
**Do not reference, copy, or mix APSDK code into this project.**  
UxPlay is the source of truth for all protocol behavior.

---

## Architecture snapshot

```
foss-mirror-tv/
  app/
    src/main/
      java/dev/fossmirror/tv/
        MainActivity.kt
        ReceiverService.kt
        ReceiverViewModel.kt
        SurfaceHostView.kt
        SettingsStore.kt
        PairingDialog.kt
        StatsOverlay.kt
      cpp/
        CMakeLists.txt
        jni/receiver_jni.cpp
        airplay/
          mdns_advertiser.cpp
          airplay_server.cpp
          pairing.cpp
          rtsp_session.cpp
          stream_demux.cpp
        media/
          video_decoder_android.cpp
          audio_decoder_android.cpp
          av_sync.cpp
        security/
          key_exchange.cpp
          pin_auth.cpp
        third_party/
          uxplay/        ← GPLv3 submodule, protocol source of truth
          rpiplay/       ← GPLv3 submodule, reference only (EXCLUDE_FROM_ALL)
          tinysvcmdns/   ← or mDNSResponder; document license
          alac/          ← Apple BSD; v1.1 only
  docs/
    ARCHITECTURE.md
    PROTOCOL_NOTES.md   ← populated from Phase 0 spike logs
    SECURITY.md
    ROADMAP.md
    TESTING.md
  scripts/
    adb-install.sh
    test-mdns.sh
    collect-logs.sh
    ci-fuzz.sh
  LICENSE
  README.md
```

---

## Epics

| ID  | Title                              | Milestone |
|-----|------------------------------------|-----------|
| E00 | Reality spike                      | spike     |
| E01 | Project skeleton & build system    | v0.1      |
| E02 | NDK: mDNS / Bonjour advertising    | v0.1      |
| E03 | NDK: AirPlay RTSP session layer    | v0.2      |
| E04 | NDK: Pairing & authentication      | v0.4      |
| E05 | NDK: Video decode pipeline         | v0.3      |
| E06 | NDK: Audio decode pipeline (AAC)   | v0.4      |
| E07 | NDK: AV sync                       | v0.4      |
| E08 | Android service & JNI bridge       | v0.2      |
| E09 | Android TV UI                      | v0.5      |
| E10 | Security hardening                 | v0.5      |
| E11 | Stability & testing                | v1.0      |

---

## Epic E00 — Reality Spike

**Purpose**: Validate all major unknowns before writing production code.
No production code is written during this phase.
Each spike produces a doc update to `docs/PROTOCOL_NOTES.md` or `docs/ARCHITECTURE.md`.
Phase 1 does not start until all six spikes are green.

---

### SPIKE-001 — Build UxPlay on macOS/Linux and confirm Mac mirroring

**Type**: Spike  
**Milestone**: spike  
**Acceptance criteria**:
- Clone `FDH2/UxPlay` (the maintained fork, not `antimof/UxPlay`)
- Build with CMake on macOS or Linux per UxPlay README
- Run `uxplay` on the same LAN as the test Mac
- macOS Screen Mirroring shows UxPlay instance within 10 seconds
- Initiate mirroring from Mac — video and audio play on the UxPlay window
- Capture full stdout/log output covering: mDNS advertisement, pairing handshake, SETUP exchange, stream start, stream parameters, stream stop
- Save log to `docs/spike-logs/uxplay-desktop.log`
- Document in `docs/PROTOCOL_NOTES.md`: exact mDNS TXT record values UxPlay advertises, RTSP method sequence, pairing method used (PIN vs auto), SDP body, codec names, payload types

**Output**: `docs/PROTOCOL_NOTES.md` section "Desktop baseline"  
**Blocker if**: UxPlay itself doesn't work on current macOS — investigate before proceeding

---

### SPIKE-002 — Capture and annotate full protocol session

**Type**: Spike  
**Milestone**: spike  
**Acceptance criteria**:
- Run UxPlay with verbose logging enabled (`-d` or equivalent)
- Capture Wireshark trace on loopback or LAN for the full mDNS + TCP session
- Annotate PROTOCOL_NOTES.md with:
    - Exact bytes/fields of `_airplay._tcp` and `_raop._tcp` TXT records
    - Full RTSP request/response transcript for OPTIONS, ANNOUNCE, SETUP, RECORD, SET_PARAMETER
    - SDP body verbatim (sanitize any private keys before committing)
    - Pairing endpoint sequence: which POSTs, which response bodies
    - AES key field name in SDP (`a=rsaaeskey:` or other)
    - Any `X-Apple-*` headers present in requests or responses
    - Codec negotiated: H.264 or H.265, AAC-ELD or AAC-LC or ALAC
- This document is the protocol ground truth for all E03/E04 implementation

**Output**: `docs/PROTOCOL_NOTES.md` section "Captured session transcript"  
**Blocker if**: pairing fails — investigate UxPlay build flags before proceeding

---

### SPIKE-003 — Android TV app with MediaCodec H.264 sample playback

**Type**: Spike  
**Milestone**: spike  
**Acceptance criteria**:
- Minimal Android app (throwaway, not in main repo) that:
    - Opens a `SurfaceView`
    - Opens a `MediaCodec` for H.264 using `AMediaCodec_createDecoderByType("video/avc")`
    - Feeds a known-good H.264 elementary stream from assets
    - Renders to surface
- Run on Sony KD-55X8000E
- Document in `docs/ARCHITECTURE.md`:
    - Output of `AMediaCodecList` enumeration — all available H.264 decoder names
    - Which codec name was selected automatically
    - Whether the selected codec is hardware or software (check for `OMX.google.*` = software)
    - Whether hardware path works at 720p and 1080p
    - Any `AMediaCodec_configure` errors encountered
- Pass/fail: hardware H.264 decode works at 1080p on the Sony TV

**Output**: `docs/ARCHITECTURE.md` section "Sony TV codec enumeration"  
**Blocker if**: no hardware H.264 decoder found — document software fallback implications

---

### SPIKE-004 — Android mDNS advertisement visible from Mac

**Type**: Spike  
**Milestone**: spike  
**Acceptance criteria**:
- Minimal Android app (throwaway) that registers `_airplay._tcp` and `_raop._tcp` via `android.net.nsd.NsdManager`
- Acquires `WifiManager.MulticastLock` first
- Run on Sony TV on same LAN as Mac
- Verify with `dns-sd -B _airplay._tcp local` on Mac that the service appears
- Document in `docs/ARCHITECTURE.md`:
    - Whether `NsdManager` is viable (latency, reliability, TXT record size limits)
    - Whether a native mDNS library (tinysvcmdns) is needed instead
    - Any multicast suppression delay observed after WiFi association
- Decision: NsdManager vs native mDNS library — final answer documented

**Output**: `docs/ARCHITECTURE.md` section "mDNS strategy decision"

---

### SPIKE-005 — License audit

**Type**: Spike  
**Milestone**: spike  
**Acceptance criteria**:
- Read full LICENSE files for: UxPlay (FDH2), RPiPlay (FD-), tinysvcmdns (or mDNSResponder), OpenSSL 3.x
- Document in `docs/ARCHITECTURE.md`:
    - Each dependency, its license, and whether it is GPL-3.0-compatible
    - UxPlay's note about OpenSSL linking (UxPlay README warns about this)
    - Whether OpenSSL 3.x Apache-2.0 is usable with GPL-3.0 app (it is, as of OpenSSL 3.0+)
    - Any files in UxPlay that reference GPL-2.0 or non-GPL components
- Decision: which crypto primitives to use — OpenSSL 3.x system library, BoringSSL (via prefab), or mbedTLS
- Confirm: APSDK is excluded entirely

**Output**: `docs/ARCHITECTURE.md` section "License audit"

---

### SPIKE-006 — Pairing flow clarification

**Type**: Spike  
**Milestone**: spike  
**Acceptance criteria**:
- From SPIKE-001/002 logs: identify exactly which pairing method macOS uses for Screen Mirroring
- Options: legacy pin-based, HAP SRP-6a, Ed25519/Curve25519 (newer), or auto-accept for same-account device
- Document the actual sequence of POST endpoints hit during pairing in PROTOCOL_NOTES.md
- Identify which UxPlay source files implement each pairing step (file names + line ranges)
- These files are what the Android port will adapt — not reimplement from scratch
- Decision: confirm "adapt UxPlay pairing code" vs "reuse UxPlay pairing code verbatim via thin wrapper"

**Output**: `docs/PROTOCOL_NOTES.md` section "Pairing flow"

---

## Epic E01 — Project Skeleton & Build System

### AIRM-001 — Initialize Gradle project

**Type**: Setup  
**Milestone**: v0.1  
**Acceptance criteria**:
- Single-module Android project: `minSdk 26`, `targetSdk 34`, `compileSdk 34`
- `applicationId "dev.fossmirror.tv"`
- Kotlin 1.9+, AGP 8.x
- NDK 26+ in `local.properties`
- `buildFeatures { viewBinding = true }`
- Java 17 source/target, `kotlinOptions.jvmTarget = "17"`
- `.gitignore`: `local.properties`, `*.keystore`, `build/`, `*.log`
- `README.md` stub with build instructions and license header

**Dependencies**: none

---

### AIRM-002 — Configure CMake for native core

**Type**: Setup  
**Milestone**: v0.1  
**Acceptance criteria**:
- `CMakeLists.txt` at `app/src/main/cpp/`
- `cmake_minimum_required(VERSION 3.22)`
- Static library `fossmirror_core` compiles all `.cpp` under `airplay/`, `media/`, `security/`, `jni/`
- Shared library `fossmirror_jni` links `fossmirror_core`
- Linked system libs: `log`, `android`, `mediandk`, `OpenSLES`, `aaudio`
- C++17: `set(CMAKE_CXX_STANDARD 17)`
- Flags: `-Wall -Wextra -fstack-protector-strong -D_FORTIFY_SOURCE=2`
- Release: `-s` (strip symbols)
- `abiFilters "arm64-v8a", "x86_64"`
- Stub `.cpp` files (empty main function or empty class) so build succeeds before implementation

**Dependencies**: AIRM-001

---

### AIRM-003 — Add UxPlay as protocol source submodule

**Type**: Setup  
**Milestone**: v0.1  
**Acceptance criteria**:
- `git submodule add https://github.com/FDH2/UxPlay app/src/main/cpp/third_party/uxplay`
- Pin to a specific commit hash; document in `docs/ARCHITECTURE.md` with date and reason
- CMake: include UxPlay protocol-layer source files selectively
    - Which files to include is determined by SPIKE-006 output, not assumed here
    - At minimum: pairing, crypto glue, RTSP parsing portions that have no desktop rendering dependency
    - Exclude: any file that includes GLib, GStreamer, X11, SDL, or GTK headers
- Create `uxplay_android_shim.h` stubbing out any rendering-side UxPlay calls with no-ops
- Build must succeed on arm64-v8a with zero unresolved desktop symbols
- RPiPlay added as `third_party/rpiplay` with `EXCLUDE_FROM_ALL` — reference only, never compiled

**Dependencies**: AIRM-002, SPIKE-001, SPIKE-006

---

### AIRM-004 — Establish logging infrastructure

**Type**: Feature  
**Milestone**: v0.1  
**Acceptance criteria**:
- C++: `native_log.h` with `LOGD`, `LOGI`, `LOGW`, `LOGE` wrapping `__android_log_print`, tag `"FossMirror"`
- All calls gated behind `#if ENABLE_LOGGING` (1 in debug, 0 in release)
- Kotlin: `AppLogger.kt` singleton wrapping `android.util.Log` with same tag
- Ring buffer: 500 lines, accessible from UI
- Log export: dumps ring buffer to `getExternalFilesDir()/fossmirror_<timestamp>.log`

**Dependencies**: AIRM-002

---

## Epic E02 — NDK: mDNS / Bonjour Advertising

### AIRM-010 — Select and embed mDNS library

**Type**: Setup  
**Milestone**: v0.1  
**Acceptance criteria**:
- Decision from SPIKE-004 is implemented here (NsdManager or native library)
- If native: embed `tinysvcmdns` (BSD) or `mDNSResponder` (Apache 2.0) under `third_party/`
- License documented in `LICENSE` and `README.md`
- If NsdManager: document TXT record size limits and any observed reliability issues from spike
- Must compile cleanly on arm64-v8a and x86_64

**Dependencies**: AIRM-002, SPIKE-004, SPIKE-005

---

### AIRM-011 — Implement `mdns_advertiser.cpp`

**Type**: Feature  
**Milestone**: v0.1  
**Acceptance criteria**:
- Class `MdnsAdvertiser` with:
    - `bool start(const std::string& receiver_name, uint16_t airplay_port, uint16_t raop_port)`
    - `void stop()`
    - `bool isRunning() const`
- TXT record values copied verbatim from `docs/PROTOCOL_NOTES.md` (SPIKE-001/002 output)
    - **Do not invent TXT record values** — use UxPlay's exact current values
    - Document every field and its source line in UxPlay
- Service names:
    - `_airplay._tcp`: `<receiver_name>._airplay._tcp.local`
    - `_raop._tcp`: `<mac_nodots>@<receiver_name>._raop._tcp.local`
- MAC address sourced from device WiFi interface via `getifaddrs()`; fallback: random-but-persistent UUID stored in prefs
- Re-announce every 30 seconds
- If feature bitmask causes macOS to negotiate unsupported features, field must be updated — do not assume `0x5A7FFFF7,0x1E` is correct without verifying against spike logs

**Dependencies**: AIRM-010, SPIKE-002

---

### AIRM-012 — Acquire multicast lock from Kotlin

**Type**: Feature  
**Milestone**: v0.1  
**Acceptance criteria**:
- `ReceiverService.kt` acquires `WifiManager.MulticastLock` (tag `"FossMirrorMDNS"`) before native start
- `WifiManager.WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) held while streaming
- Both released in `onDestroy()`
- Reference counted correctly — no double-acquire
- Manifest: `android.permission.CHANGE_WIFI_MULTICAST_STATE`
- Handle multicast suppression delay observed in SPIKE-004: retry mDNS start with exponential backoff from 2s if no advertisement confirmed

**Dependencies**: AIRM-011

---

### AIRM-013 — Validate: Mac sees TV in Screen Mirroring

**Type**: Test / Milestone gate  
**Milestone**: v0.1  
**Acceptance criteria**:
- `scripts/test-mdns.sh` runs `dns-sd -B _airplay._tcp local` on same-LAN Mac
- App running on Sony TV → device name appears in macOS Screen Mirroring within 10 seconds
- This is the v0.1 milestone gate — v0.1 is not complete until this passes

**Dependencies**: AIRM-012

---

## Epic E03 — NDK: AirPlay RTSP Session Layer

### AIRM-020 — Implement TCP server socket

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- `airplay_server.cpp`: class `AirPlayServer`
- Binds TCP on configurable port (default 7000) on `0.0.0.0`
- Accept loop in dedicated thread
- Each accepted connection spawns one `RtspSession`
- Max 1 concurrent session: incoming connection while session active tears down existing session, then accepts new
- Socket options: `SO_REUSEADDR`, `TCP_NODELAY`
- Graceful shutdown: signals accept loop, joins thread, closes socket

**Dependencies**: AIRM-004

---

### AIRM-021 — Implement RTSP request parser

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- `rtsp_session.cpp`: class `RtspSession`
- Parses RTSP/1.0 format: request line, headers (case-insensitive key matching), body
- Methods handled per `docs/PROTOCOL_NOTES.md` session transcript (not assumed): at minimum OPTIONS, ANNOUNCE, SETUP, RECORD, SET_PARAMETER, GET_PARAMETER, TEARDOWN, FLUSH
- Responses: well-formed RTSP/1.0, `CSeq` echoed
- `X-Apple-*` headers passed through to handler callbacks — do not strip
- All parsing is length-checked: max 8KB per header line, max 32 headers, max 64KB body
- Malformed requests → `400 Bad Request`, connection closed
- No `strcpy`, no unbounded `sscanf`, no `atoi` without bounds check
- Session runs in its own thread; thread-safe callbacks to `AirPlayServer`
- **Protocol behavior is driven by `docs/PROTOCOL_NOTES.md`** — any field not documented there is deferred

**Dependencies**: AIRM-020, SPIKE-002

---

### AIRM-022 — Implement SDP parser

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- `sdp_parser.cpp`: `bool parseSdp(const std::string& body, SdpInfo* out)`
- `SdpInfo` fields derived from actual SPIKE-002 SDP capture — not assumed
    - Fields at minimum: `video_codec`, `video_payload_type`, `audio_codec`, `audio_payload_type`, `audio_sample_rate`, `audio_channels`, `clock_rate`, AES key field (name confirmed by SPIKE-002)
    - SPS/PPS from `a=fmtp:` `sprop-parameter-sets=` if present
- Fuzz-safe: all lengths bounded, returns false on malformed input
- Unit test `tests/sdp_parser_test.cpp` using a real captured SDP from SPIKE-002 as a test fixture
- Additional test cases: missing video, missing audio, oversized field (>4KB SDP body → return false), non-UTF-8

**Dependencies**: AIRM-021, SPIKE-002

---

### AIRM-023 — Implement RTP stream receiver

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- `stream_demux.cpp`: class `StreamDemux`
- Receives UDP on video and audio ports negotiated during SETUP
- RTP header parsed: version, PT, sequence number, timestamp, SSRC
- Out-of-order detection: sequence number tracking with 64-packet reorder buffer; packets older than window discarded with `LOGW`
- H.264 NAL unit reassembly from RTP FU-A fragmentation (RFC 6184)
- Video callback: `std::function<void(const uint8_t*, size_t, uint32_t pts)>`
- Audio callback: `std::function<void(const uint8_t*, size_t, uint32_t pts)>`
- Runs in dedicated receive thread; callbacks fire on that thread
- UDP receive buffer size: 256KB via `SO_RCVBUF`

**Dependencies**: AIRM-022

---

### AIRM-024 — Implement AirPlay clock sync

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- Clock sync behavior copied from UxPlay implementation — do not derive from RTSP RFC
- Processes `SET_PARAMETER` clock sync packets per UxPlay handling
- Maintains rolling 8-sample average for offset estimation
- `int64_t getOffsetUs() const`, `uint64_t nowUs() const`
- Thread-safe: offset updated on RTSP thread, `nowUs()` called from decoder threads

**Dependencies**: AIRM-023, SPIKE-002

---

## Epic E04 — NDK: Pairing & Authentication

### AIRM-030 — Adapt UxPlay pairing implementation

**Type**: Feature  
**Milestone**: v0.4  
**Acceptance criteria**:
- **Do not implement SRP, Curve25519, Ed25519, AES, HKDF, or RSA from scratch**
- Identify pairing source files in UxPlay per SPIKE-006 output
- Adapt those files for Android: replace any desktop I/O (stdin PIN entry, GLib event loop) with callback interface
- Callback interface:
    - `onPairingRequested(device_name)` → fires JNI event to show PIN dialog
    - `onPairingSucceeded(device_id, device_name)`
    - `onPairingFailed(reason)`
- Crypto primitives via OpenSSL 3.x (confirmed GPL-3.0 compatible per SPIKE-005) or whichever library SPIKE-005 selected
- PIN stored in memory only; invalidated after 60 seconds
- Rate limiting: 5 failed attempts per IP → 30-second lockout

**Dependencies**: AIRM-021, SPIKE-006, SPIKE-005

---

### AIRM-031 — Session encryption key derivation

**Type**: Feature  
**Milestone**: v0.4  
**Acceptance criteria**:
- Key derivation approach taken directly from UxPlay `key_exchange` equivalent
- Do not assume HKDF-SHA512 or any specific KDF — verify against SPIKE-006/002 before implementing
- Decryption key/IV passed to `StreamDemux` after successful pair-verify
- Session keys stored in `RtspSession`, scoped to session lifetime

**Dependencies**: AIRM-030

---

### AIRM-034 — AES media key from ANNOUNCE body

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- AES key field name and encryption scheme taken from `docs/PROTOCOL_NOTES.md` (SPIKE-002), not assumed
- RSA-2048 keypair generated at first launch, private key in Android Keystore (`"fossmirror_pairing_rsa"`)
- Public key DER hex-encoded for `pk=` mDNS TXT record
- Decrypt per UxPlay behavior — verify against spike before coding
- Decrypted AES key+IV passed to `StreamDemux`

**Dependencies**: AIRM-022, SPIKE-002

---

### AIRM-035 — AES stream decryption

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- Decryption behavior (mode, which bytes are encrypted, block boundaries) copied from UxPlay — do not invent
- First implementation may use a copy — correctness first
- Add a packet replay test: capture an encrypted stream packet from spike, verify decrypted output matches expected
- Optimize to in-place only after 720p mirroring works end-to-end

**Dependencies**: AIRM-034

---

### AIRM-032 — Persist trusted devices

**Type**: Feature  
**Milestone**: v0.5  
**Acceptance criteria**:
- `SettingsStore.kt` stores trusted device list in `EncryptedSharedPreferences`
- Schema: `{ "trusted_devices": [ { "device_id": "...", "device_name": "...", "added_at": epoch_ms } ] }`
- Max 20 entries; oldest evicted when full
- JNI: `isTrusted(device_id: String): Boolean`
- UI: trusted device list in settings (AIRM-063)
- UI: "Remove trust" per device

**Dependencies**: AIRM-031

---

### AIRM-033 — Pairing mode setting

**Type**: Feature  
**Milestone**: v0.5  
**Acceptance criteria**:
- `PairingMode`: `ALWAYS_ASK`, `TRUSTED_ONLY`, `OPEN`
- `OPEN` hidden behind developer options toggle (7x tap on version number to enable dev mode)
- Mode stored in `SettingsStore`

**Dependencies**: AIRM-032

---

## Epic E05 — NDK: Video Decode Pipeline

### AIRM-040 — Codec enumeration and hardware selection

**Type**: Feature  
**Milestone**: v0.3  
**Acceptance criteria**:
- `codec_selector.cpp`: `std::string selectH264Decoder(bool allow_software)`
- Iterates `AMediaCodecList`, collects all decoders for `"video/avc"`
- Prefers hardware: any codec whose name does NOT start with `"OMX.google."` and does NOT contain `"sw"` (case-insensitive)
    - Hardware examples: `OMX.qcom.*`, `OMX.MTK.*`, `OMX.amlogic.*`, `c2.*`
- If no hardware codec found and `allow_software == false`: return empty string, caller emits error
- If `allow_software == true` (dev mode only): allow `OMX.google.h264.decoder`
- Log selected codec name at `LOGI` level always
- Sony TV codec names documented in SPIKE-003 used to verify this logic

**Dependencies**: AIRM-002, SPIKE-003

---

### AIRM-041 — Implement `video_decoder_android.cpp`

**Type**: Feature  
**Milestone**: v0.3  
**Acceptance criteria**:
- Class `VideoDecoder` using AMediaCodec NDK
- `bool init(ANativeWindow* window, int width, int height, const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len, const std::string& codec_name)`
    - Creates codec by name (from AIRM-040), not by MIME type — ensures hardware path
    - Configures `AMediaFormat`: `KEY_WIDTH`, `KEY_HEIGHT`, `KEY_MIME`, `csd-0` (SPS), `csd-1` (PPS)
    - Surface output to `ANativeWindow`
    - Returns false and logs if `AMediaCodec_configure` fails; caller handles gracefully
- `void decode(const uint8_t* nal, size_t len, int64_t pts_us)`
    - Queues to input buffer, 2ms timeout
    - Drops frame if no buffer available — logs warning with drop counter
- Background drain thread: `AMediaCodec_dequeueOutputBuffer` → `AMediaCodec_releaseOutputBuffer(..., true)`
- `void stop()`: flush, stop drain thread, release codec

**Dependencies**: AIRM-040

---

### AIRM-042 — H.264 Annex B conversion

**Type**: Feature  
**Milestone**: v0.3  
**Acceptance criteria**:
- AirPlay sends H.264 in AVCC (4-byte length prefix per NAL unit)
- `stream_demux.cpp` converts to Annex B (`\x00\x00\x00\x01` start code) before video callback
- Multi-NAL packets (multiple length-prefixed NALs in one payload) fully unwrapped
- Conversion uses a pre-allocated scratch buffer (max 2MB); no heap alloc per frame

**Dependencies**: AIRM-041

---

### AIRM-043 — SPS/PPS extraction

**Type**: Feature  
**Milestone**: v0.3  
**Acceptance criteria**:
- `sdp_parser.cpp` extracts `sprop-parameter-sets` from `a=fmtp:` line (base64 decoded)
- SPS and PPS bytes stored in `SdpInfo`; passed to `VideoDecoder::init()`
- If not present in SDP: defer init until first IDR NAL unit; extract SPS/PPS from stream in-band

**Dependencies**: AIRM-022, AIRM-041

---

### AIRM-044 — Surface handoff from Kotlin to NDK

**Type**: Feature  
**Milestone**: v0.3  
**Acceptance criteria**:
- `SurfaceHostView.kt`: `SurfaceView` subclass
- `surfaceCreated` → JNI `NativeReceiver.setSurface(surface)`
- JNI: `ANativeWindow_fromSurface(env, surface)` → passed to `VideoDecoder::init()`
- `surfaceDestroyed` → JNI `NativeReceiver.clearSurface()` → `ANativeWindow_release()`
- SurfaceView: `setKeepScreenOn(true)` while streaming

**Dependencies**: AIRM-041

---

### AIRM-045 — Resolution cap handling

**Type**: Feature  
**Milestone**: v0.3  
**Acceptance criteria**:
- `max_width` / `max_height` from settings (default 1280×720, optional 1920×1080)
- **Do not assert that macOS respects a SETUP-negotiated cap** — this may not be reliable
- Approach: accept whatever resolution macOS sends; if it exceeds cap, `VideoDecoder` is init'd at actual stream resolution (MediaCodec scales to surface)
- Log actual stream resolution at stream start
- If negotiation via SETUP headers is confirmed working from SPIKE-002 logs, it may be added as an enhancement — not a baseline assumption

**Dependencies**: AIRM-043

---

### AIRM-046 — H.265/HEVC decoder (experimental, v1.0)

**Type**: Feature  
**Milestone**: v1.0  
**Acceptance criteria**:
- `codec_selector.cpp` extended to enumerate HEVC decoders (`"video/hevc"`)
- Same hardware-first preference logic
- Gate behind `ExperimentalFeatures.HEVC_DECODE` setting, disabled by default
- If HEVC codec unavailable: reject HEVC stream with RTSP `488 Not Acceptable Here`

**Dependencies**: AIRM-040

---

## Epic E06 — NDK: Audio Decode Pipeline (AAC first)

### AIRM-050 — Implement `audio_decoder_android.cpp` (AAC only)

**Type**: Feature  
**Milestone**: v0.4  
**Note**: AAC-ELD is the primary macOS mirroring audio codec per SPIKE-002. ALAC deferred to v1.1.
**Acceptance criteria**:
- Class `AudioDecoder` using AMediaCodec
- `bool init(int sample_rate, int channels, const uint8_t* asc, size_t asc_len)`
    - `asc` = AudioSpecificConfig bytes from `a=fmtp:` SDP line
    - MIME `"audio/mp4a-latm"`, `KEY_AAC_PROFILE = AACObjectELD (39)` for AAC-ELD
    - If SDP shows AAC-LC: use `AACObjectLC (2)` — do not assume ELD without verifying spike
- `void decode(const uint8_t* payload, size_t len, int64_t pts_us)`
- Output PCM pushed to `AudioSink` (AIRM-051)
- If `AMediaCodec_configure` fails for AAC: log codec name and error; emit `onError`

**Dependencies**: AIRM-023, SPIKE-002

---

### AIRM-051 — AAudio output sink

**Type**: Feature  
**Milestone**: v0.4  
**Acceptance criteria**:
- `AudioSink`: opens AAudio stream
    - `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`
    - `AAUDIO_SHARING_MODE_EXCLUSIVE` preferred, fallback `SHARED`
    - Sample rate and channel count from `SdpInfo`
    - Format `AAUDIO_FORMAT_PCM_I16`
- Fallback to OpenSL ES if AAudio unavailable (API < 26)
- **No volume normalization in MVP** — passthrough PCM only
- Volume normalization is a v1.1 feature; do not add complexity that can introduce latency or clipping bugs before baseline works

**Dependencies**: AIRM-050

---

### AIRM-052 — ALAC fallback (v1.1, deferred)

**Type**: Feature  
**Milestone**: v1.1  
**Note**: Do not implement before AAC mirroring is stable. Blocking on ALAC is scope creep.
**Acceptance criteria** (deferred):
- Embed `libALAC` (Apple BSD) under `third_party/alac/`
- `AudioDecoder` uses it when MediaCodec ALAC unavailable
- License documented in `LICENSE`

**Dependencies**: AIRM-051

---

## Epic E07 — NDK: AV Sync

### AIRM-055 — AV presentation clock

**Type**: Feature  
**Milestone**: v0.4  
**Acceptance criteria**:
- Class `AvSync`
- `render_ahead_us` default 80ms (40ms–200ms configurable)
- Video: `AMediaCodec_releaseOutputBuffer` deferred until `pts >= clock.nowUs() + render_ahead_us`
- Audio: PCM fed to AAudio governed by AudioTrack clock; PTS tracks divergence
- A/V divergence > 150ms: drop video frames to let audio lead (audio is dominant)
- A/V divergence > 500ms: drop all buffered audio, flush video decoder, emit `onStreamReset`
- `StatsOverlay` reads `getAvOffsetMs()` every 500ms

**Dependencies**: AIRM-050, AIRM-041

---

### AIRM-056 — Audio jitter buffer

**Type**: Feature  
**Milestone**: v0.4  
**Acceptance criteria**:
- Ring buffer holding 250ms of decoded PCM
- `AudioDecoder` writes; `AudioSink` reads
- Below 20ms fill: insert silence (concealment), log underrun
- Above 220ms fill: drop audio samples in small blocks to drain — do not attempt time-stretching or pitch correction; simple sample dropping is acceptable and honest
- `getAudioBufferMs()` exposed for stats overlay

**Dependencies**: AIRM-055

---

## Epic E08 — Android Service & JNI Bridge

### AIRM-060 — Implement `ReceiverService.kt`

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- `ReceiverService` extends `Service`; declared as foreground service in manifest
- `startForeground()` in `onStartCommand` with notification (channel `"receiver"`)
- Notification: icon, "FossMirror — Receiver Active", Stop action
- Multicast lock + WiFi lock acquired `onStartCommand`, released `onDestroy`
- `NativeReceiver.start(...)` when surface ready; `NativeReceiver.stop()` in `onDestroy`
- `LocalBroadcastManager` broadcasts: `ACTION_RECEIVER_STARTED`, `ACTION_CLIENT_CONNECTED`, `ACTION_STREAM_STARTED`, `ACTION_STREAM_STOPPED`, `ACTION_ERROR`
- `START_STICKY`: service restarts after process death (foreground service manager behavior)

**Dependencies**: AIRM-044

---

### AIRM-061 — Implement JNI bridge `receiver_jni.cpp`

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- Registered native methods for `NativeReceiver` Kotlin object:
    - `start(name: String, widthCap: Int, heightCap: Int, surface: Surface): Int`
    - `stop()`
    - `setPin(pin: String?)`
    - `getStats(): ByteArray` (JSON)
    - `setLogLevel(level: Int)`
    - `setSurface(surface: Surface)`
    - `clearSurface()`
- Reverse callbacks to Kotlin (fired on dedicated `HandlerThread`):
    - `onReceiverStarted(port: Int)`
    - `onClientDiscovered(name: String, ip: String)`
    - `onPairingRequested(deviceName: String)`
    - `onStreamStarted(width: Int, height: Int, fps: Int, codec: String)`
    - `onStreamStopped(reason: String)`
    - `onError(code: Int, message: String)`
- **C++ exception handling at every JNI boundary**: every exported function wrapped in `try { ... } catch (const std::exception& e) { callJavaError(e.what()); }` — prevents exception propagating into JVM
- **No SIGSEGV recovery**: do not install signal handlers for the purpose of continuing after a segfault; that is undefined behavior. If native code segfaults, Android generates a tombstone — collect it with `scripts/collect-logs.sh`. The service may restart via `START_STICKY`.
- `JavaVM*` cached in `JNI_OnLoad`; env obtained per-call via `AttachCurrentThread`; global refs managed correctly

**Dependencies**: AIRM-060

---

### AIRM-062 — Implement `ReceiverViewModel.kt`

**Type**: Feature  
**Milestone**: v0.2  
**Acceptance criteria**:
- `ReceiverViewModel` extends `ViewModel`
- `StateFlow<ReceiverState>`:
  ```kotlin
  sealed class ReceiverState {
      object Idle : ReceiverState()
      object Starting : ReceiverState()
      data class Advertising(val name: String) : ReceiverState()
      data class ClientConnected(val deviceName: String, val ip: String) : ReceiverState()
      data class Streaming(val width: Int, val height: Int, val fps: Int, val codec: String) : ReceiverState()
      data class Error(val message: String) : ReceiverState()
  }
  ```
- `StateFlow<ReceiverStats>` updated every 1 second while streaming
- `ReceiverStats`: `bitrateKbps: Int`, `fps: Float`, `frameDrops: Int`, `audioBufferMs: Int`, `avOffsetMs: Int`
- `startReceiver()` / `stopReceiver()` bound to service lifecycle

**Dependencies**: AIRM-061

---

## Epic E09 — Android TV UI

### AIRM-063 — Implement `MainActivity.kt`

**Type**: Feature  
**Milestone**: v0.5  
**Acceptance criteria**:
- `AppCompatActivity` with leanback theme (`Theme.Leanback`)
- `SurfaceHostView` fills screen when streaming
- D-pad navigable status panel when not streaming:
    - Receiver name (editable)
    - Receiver ON/OFF toggle
    - Pairing mode: Always Ask / Trusted Only
    - Resolution cap: 720p / 1080p
    - Stats overlay toggle (dev mode only)
    - Log export button
    - App version + commit hash
- D-pad UP while streaming: show/hide overlay (auto-hide after 5s)
- Back while streaming: confirmation dialog before stop

**Dependencies**: AIRM-062

---

### AIRM-064 — Implement `PairingDialog.kt`

**Type**: Feature  
**Milestone**: v0.5  
**Acceptance criteria**:
- `DialogFragment` shown on `onPairingRequested`
- Device name + 4-digit PIN in large text (min 72sp — readable from couch)
- 60-second countdown timer; dismiss and reject on timeout
- "Trust this device" checkbox (default unchecked)
- D-pad navigable
- On confirm: calls `NativeReceiver.setPin(pin)`

**Dependencies**: AIRM-063

---

### AIRM-065 — Implement `StatsOverlay.kt`

**Type**: Feature  
**Milestone**: v0.5  
**Acceptance criteria**:
- Semi-transparent corner overlay
- Shows: bitrate, FPS, frame drops, audio buffer ms, A/V offset ms
- Updates every 500ms from `ReceiverViewModel.statsFlow`
- Visible only when dev mode + stats enabled in settings
- Min 14sp, high-contrast white on semi-transparent dark background

**Dependencies**: AIRM-063

---

### AIRM-066 — Implement `SettingsStore.kt`

**Type**: Feature  
**Milestone**: v0.5  
**Acceptance criteria**:
- Jetpack `DataStore<Preferences>`
- Keys: `receiver_name`, `pairing_mode`, `max_width`, `max_height`, `trusted_devices_json`, `dev_mode_enabled`, `stats_overlay_enabled`, `log_level`, `software_decoder_allowed`
- Defaults: `receiver_name = Build.MODEL.take(32)`, `pairing_mode = ALWAYS_ASK`, `max_width = 1280`, `max_height = 720`, `dev_mode_enabled = false`, `software_decoder_allowed = false`
- Flow-based reads; suspend writes

**Dependencies**: AIRM-062

---

## Epic E10 — Security Hardening

### AIRM-070 — Packet size enforcement

**Type**: Security  
**Milestone**: v0.5  
**Acceptance criteria**:
- RTSP: max 8KB per header line, max 32 headers, max 64KB body
- SDP body: max 4KB
- UDP payload: max 65507 bytes (protocol hard cap — not Ethernet MTU); warn above 8KB per packet
- Oversized input: log peer IP + size, close connection
- All buffers pre-sized; no heap allocation proportional to attacker-controlled length field

**Dependencies**: AIRM-021, AIRM-023

---

### AIRM-071 — Connection throttling and LAN-only

**Type**: Security  
**Milestone**: v0.5  
**Acceptance criteria**:
- Max 5 connections per IP per 30 seconds (tracked in `std::unordered_map<std::string, RateEntry>`)
- Pairing failures: 5 per IP per 60 seconds → 30s lockout
- Reject connections from non-RFC-1918 / non-link-local IPs: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`
- Log all rejections with reason and peer IP

**Dependencies**: AIRM-070

---

### AIRM-072 — Crash handling and tombstone collection

**Type**: Stability  
**Milestone**: v0.5  
**Note (corrected from v1 backlog)**: Native SIGSEGV kills the process — this cannot be safely caught and continued from. The correct approach is tombstone collection and service restart.
**Acceptance criteria**:
- C++ exceptions caught at all JNI boundaries (AIRM-061)
- `ReceiverService` declared `START_STICKY`: Android restarts it after process death with 5s delay
- `scripts/collect-logs.sh`: ADB script that pulls `/data/tombstones/` and logcat from device
- On restart after crash: `ReceiverService` clears "streaming" state, shows "Receiver restarted after crash" in notification
- Do not attempt `sigaction(SIGSEGV, ...)` recovery — document this as out of scope in `docs/ARCHITECTURE.md`

**Dependencies**: AIRM-061

---

### AIRM-073 — No WAN exposure

**Type**: Security  
**Milestone**: v0.5  
**Acceptance criteria**:
- Before starting server: check primary interface IP is RFC 1918 or link-local
- If public IP detected on primary interface: emit `ERROR_PUBLIC_NETWORK`, block server start, show warning in UI
- VPN detection: if `NetworkCapabilities.NET_CAPABILITY_NOT_VPN` absent, warn user mDNS may not reach LAN peers

**Dependencies**: AIRM-071

---

### AIRM-074 — Fuzz native parsers

**Type**: Security  
**Milestone**: v1.0  
**Acceptance criteria**:
- `scripts/fuzz/fuzz_rtsp.cpp`: libFuzzer harness for `RtspSession::parseRequest()`
- `scripts/fuzz/fuzz_sdp.cpp`: harness for `parseSdp()`
- `scripts/fuzz/fuzz_rtp.cpp`: harness for `StreamDemux::handlePacket()`
- CMake target `fossmirror_fuzz` builds with `-fsanitize=address,fuzzer`
- `scripts/ci-fuzz.sh`: runs each for 60 seconds; any crash = P0 bug + regression test added

**Dependencies**: AIRM-070

---

## Epic E11 — Stability & Testing

### AIRM-080 — Unit tests for protocol layer

**Type**: Test  
**Milestone**: v1.0  
**Acceptance criteria**:
- CMake target `fossmirror_tests` (GoogleTest)
- Test fixtures sourced from actual SPIKE-002 captures where possible
- `rtsp_parser_test.cpp`: all handled methods, malformed input, oversized headers, missing CSeq
- `sdp_parser_test.cpp`: real captured SDP from spike, missing codec, malformed base64
- `rtp_demux_test.cpp`: in-order, out-of-order, packet loss, duplicates
- `mdns_test.cpp`: TXT record assembly, service name formatting
- All pass with `-fsanitize=address,undefined`

**Dependencies**: AIRM-022, AIRM-023

---

### AIRM-081 — Integration test: Mac sees TV

**Type**: Test / Gate  
**Milestone**: v0.1  
**Acceptance criteria**:
- `docs/TESTING.md` manual procedure documented
- Sony TV with app running → device name in macOS Screen Mirroring within 10s
- v0.1 gate

**Dependencies**: AIRM-013

---

### AIRM-082 — Integration test: Mac streams to TV

**Type**: Test / Gate  
**Milestone**: v0.3  
**Acceptance criteria**:
- `docs/TESTING.md` documents:
    1. Start receiver
    2. Mac Screen Mirroring → pairing dialog on TV
    3. After pairing: video on TV at configured resolution, audio in sync
- Test matrix results recorded:
    - macOS Sequoia 720p cap
    - macOS Sequoia 1080p cap
    - iPhone iOS 17 720p (same RTSP stack)
- v0.3 gate

**Dependencies**: AIRM-044, AIRM-050

---

### AIRM-083 — Reconnect stability test

**Type**: Test  
**Milestone**: v1.0  
**Acceptance criteria**:
- `scripts/test-reconnect.sh`: 10 connect/disconnect cycles
- After each reconnect: streaming resumes within 15s
- Zero app crashes over 10 cycles
- Any crash: logcat + tombstone attached to bug

**Dependencies**: AIRM-082

---

### AIRM-084 — Memory check

**Type**: Test  
**Milestone**: v1.0  
**Acceptance criteria**:
- `valgrind --leak-check=full` on `fossmirror_tests` (host x86_64 build)
- AddressSanitizer build on device for 10-minute stream
- Zero definite leaks in `airplay/`, `media/`, `security/`
- Third-party leaks noted but not required to fix

**Dependencies**: AIRM-080

---

### AIRM-085 — Performance baseline

**Type**: Test  
**Milestone**: v1.0  
**Acceptance criteria**:
- 60-second 1080p/30fps stream on Sony KD-55X8000E, stats captured from overlay
- Targets:
    - Sustained FPS ≥ 28 (≥ 93% of 30fps)
    - Frame drops < 5%
    - A/V offset < ±80ms sustained
    - Audio underruns < 2/minute
    - CPU < 40% single core sustained
- If targets not met: profiling notes in `docs/TESTING.md`

**Dependencies**: AIRM-082

---

### AIRM-086 — CI build and APK artifact

**Type**: DevOps  
**Milestone**: v1.0  
**Acceptance criteria**:
- `scripts/build-release.sh`: `./gradlew assembleRelease`
- Debug APK signed with project debug keystore (committed under `scripts/debug.keystore`) — **labeled as debug artifact only in README; not for distribution**
- Release signing: documented as manual step using private keystore never committed to repo
- GitHub Actions: push to `main` → build APK + unit tests + upload APK as artifact
- `README.md`: feature list, known limitations, build instructions, sideload instructions, license notice

**Dependencies**: AIRM-083, AIRM-084

---

## Implementation order (for AI agent)

```
Phase 0 — Reality Spike (do first, no code)
  SPIKE-001 → SPIKE-002 → SPIKE-003 → SPIKE-004 → SPIKE-005 → SPIKE-006
  Gate: all six spikes documented before Phase 1 starts

Phase 1 — Android shell (v0.1)
  AIRM-001 → AIRM-002 → AIRM-003 → AIRM-004
  AIRM-010 → AIRM-011 → AIRM-012 → AIRM-013
  Gate: AIRM-081 (Mac sees TV)

Phase 2 — Session skeleton (v0.2)
  AIRM-020 → AIRM-021 → AIRM-022 → AIRM-034
  AIRM-023 → AIRM-035 → AIRM-024
  AIRM-060 → AIRM-061 → AIRM-062

Phase 3 — Video (v0.3)
  AIRM-040 → AIRM-041 → AIRM-042 → AIRM-043 → AIRM-044 → AIRM-045
  Gate: AIRM-082 (Mac streams to TV)

Phase 4 — Audio + AV sync (v0.4)
  AIRM-050 → AIRM-051
  AIRM-055 → AIRM-056
  AIRM-030 → AIRM-031

Phase 5 — UI + Security (v0.5)
  AIRM-066 → AIRM-063 → AIRM-064 → AIRM-065
  AIRM-032 → AIRM-033
  AIRM-070 → AIRM-071 → AIRM-072 → AIRM-073

Phase 6 — Polish + Release (v1.0)
  AIRM-046 → AIRM-074
  AIRM-080 → AIRM-083 → AIRM-084 → AIRM-085 → AIRM-086
```

---

## Critical rules for AI agent (read before each session)

1. **Protocol behavior comes from `docs/PROTOCOL_NOTES.md`**, not from RFCs, not from this backlog's field names. If PROTOCOL_NOTES.md doesn't yet document a field, the spike is incomplete — do not guess.

2. **UxPlay is the source of truth.** When in doubt about any protocol behavior, pairing step, or crypto operation, find the relevant UxPlay source file first. Adapt it; do not reimplement it.

3. **`OMX.google.*` is software.** Never select it as the decoder for production paths. Always enumerate codecs and log the selected name. Require explicit dev-mode flag for software decode.

4. **Do not implement crypto primitives from scratch.** SRP, Curve25519, Ed25519, AES, HKDF, RSA: use OpenSSL 3.x (GPL-3.0 compatible) or the implementation already in UxPlay.

5. **Native crashes are tombstones, not exceptions.** SIGSEGV recovery is undefined behavior. Catch `std::exception` at JNI boundaries. Let Android generate tombstones. Collect with `scripts/collect-logs.sh`.

6. **Correctness before optimization.** AES decryption may copy. Jitter buffer may use simple sample drop. Do not add time-stretching, normalization, or zero-copy tricks until baseline streaming works.

7. **APSDK is excluded.** Do not reference, include, or copy any file from `air-display/apsdk-public`. GPL-2.0 with private FairPlay dependency — incompatible with this project's GPL-3.0 stance.

8. **RTP max size is 65507 bytes**, not 1500. Do not reject valid packets because they exceed Ethernet MTU after IP reassembly.

9. **Resolution negotiation may not work.** Do not assert macOS respects SETUP header caps. Accept actual stream resolution; log it; let MediaCodec handle it.

10. **ALAC is v1.1.** Do not implement ALAC before AAC mirroring is stable. It will block progress.
```