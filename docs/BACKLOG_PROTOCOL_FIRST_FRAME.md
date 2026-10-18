# FossMirrorTV — Corrected Protocol Backlog

## Goal

Show one real macOS screen-mirroring frame on Android TV.

Success is not "Mac connects."
Success is not "RTSP reaches SETUP."
Success is not "color bars render."
Success is:

> one decrypted macOS IDR frame reaches MediaCodec and appears on the TV.

---

# P0 — Backlog Truth Fixes

## FMR-P0-001 — Remove fake protocol confidence from backlog

**Priority:** P0  
**Status:** TODO  
**Area:** Documentation / Protocol truth

### Problem

The current backlog mixes assumptions:
- UDP/RTP language appears in places even though current implementation uses AirPlay mirror TCP framing.
- AES-CBC is mentioned as if confirmed.
- Current fake pairing/FairPlay responses can trick future agents into thinking the protocol path is closer than it is.

### Required Change

Update backlog wording to say:

- Current implementation path is RTSP control on `7000` plus mirror TCP data on `7100`.
- Do not implement UDP/RTP unless packet capture proves macOS is using it.
- Do not assume AES-CBC or AES-CTR by name in backlog until the imported/reference implementation confirms the actual mirror payload decrypt path.
- Pairing/FairPlay/decryption is the real blocker.
- Fake success responses are spike-only and must be removed or clearly marked.

### Acceptance Criteria

- No backlog item tells the agent to implement RTP/FU-A unless a capture proves it.
- No backlog item says "AES-CBC" as a fact without capture/reference evidence.
- Backlog clearly says decrypted video payload must exist before NAL parsing.
- Backlog clearly says fake `/pair-*` and `/fp-setup` success is not acceptable.

### Done Means Done

A future LLM cannot read the backlog and mistakenly implement the wrong transport or wrong crypto mode.

---

# P0 — Stop Fake Protocol Success

## FMR-P0-002 — Replace fake `/pair-setup`, `/pair-verify`, and `/fp-setup` success

**Priority:** P0  
**Status:** TODO  
**Area:** RTSP / Pairing / FairPlay

### Problem

Current handlers return successful responses even though real pairing, verification, and FairPlay setup are not implemented.

This is poison. It creates fake progress.

### Required Change

Replace fake success with explicit protocol state handling:

```text
PAIRING_NOT_IMPLEMENTED
PAIR_VERIFY_NOT_IMPLEMENTED
FAIRPLAY_NOT_IMPLEMENTED
SESSION_KEYS_NOT_READY
```

Temporary behavior:

- Either return `501 Not Implemented`, or
- Return only real adapter-produced responses.

No empty success.
No zeroed FairPlay bodies.
No placeholder "OK" pretending to work.

### Files Likely Affected

```text
app/src/main/cpp/airplay_server.cpp
app/src/main/cpp/airplay_server.h
```

### Acceptance Criteria

- `/pair-setup` does not return success unless real pair-setup output exists.
- `/pair-verify` does not return success unless real verification output exists.
- `/fp-setup` does not return placeholder bytes.
- Logs clearly show which stage failed.

### Verification

```bash
./gradlew :app:assembleDebug
adb logcat | grep -E "pair|verify|fp-setup|FairPlay|FossAirPlay"
```

### Done Means Done

A macOS client cannot advance due to fake responses. It either gets real protocol responses or a clear failure.

---

# P0 — Pairing / FairPlay / Crypto Adapter

## FMR-P0-003 — Add native pairing and FairPlay adapter boundary

**Priority:** P0
**Status:** TODO
**Area:** Native crypto integration

### Problem

The app needs a clean adapter boundary before importing or adapting any reference pairing/FairPlay code.

### Required Change

Create:

```text
app/src/main/cpp/airplay_crypto/
  airplay_crypto_adapter.h
  airplay_crypto_adapter.cpp
  airplay_crypto_session.h
  airplay_crypto_session.cpp
```

Expose a narrow API:

```cpp
struct CryptoResult {
    bool ok;
    std::vector<uint8_t> response;
    std::string error;
};

CryptoResult handle_pair_setup(const uint8_t* body, size_t len);
CryptoResult handle_pair_verify(const uint8_t* body, size_t len);
CryptoResult handle_fp_setup(const uint8_t* body, size_t len);

bool has_video_decryptor();

DecryptResult decrypt_mirror_video_payload(
    const uint8_t* encrypted,
    size_t encrypted_len
);
```

### Hard Rule

Do not invent cryptography.

Use reference behavior from captured logs / UxPlay-derived implementation / validated library code.

### Acceptance Criteria

- RTSP handlers call adapter functions.
- Session key state is held per RTSP session, not as anonymous global state.
- Decrypt function refuses to run before keys are ready.
- No crypto key material is logged.

### Verification

```bash
./gradlew :app:assembleDebug
adb logcat | grep -E "CryptoAdapter|pair|fp-setup|session keys"
```

### Done Means Done

There is a real adapter seam where protocol handlers can plug into real pairing/FairPlay logic without polluting `airplay_server.cpp`.

---

## FMR-P0-004 — Port or integrate real reference pairing/FairPlay implementation

**Priority:** P0
**Status:** TODO
**Area:** Pairing / FairPlay / Legal

### Problem

macOS mirror payloads cannot be decoded until the app completes pairing/FairPlay and derives usable video decrypt state.

### Required Change

Integrate real reference implementation through `airplay_crypto_adapter`.

Options:

1. Direct source port for personal/GPL-compatible project.
2. Clean-room adapter if proprietary distribution is intended.
3. External native library if easier to maintain.

### Required Notes

Document:

```text
docs/protocol/crypto-source.md
```

Include:

- source of implementation,
- license impact,
- copied files,
- modified files,
- crypto backend used,
- what keys are derived,
- which decrypt mode is actually used,
- evidence from logs/captures.

### Acceptance Criteria

- `/pair-setup` produces non-empty real response.
- `/pair-verify` produces non-empty real response.
- `/fp-setup` produces real response.
- Session reaches "video decryptor ready."
- Decrypt mode is documented from source/capture, not guessed.

### Verification

```bash
adb logcat | grep -E "pair-setup|pair-verify|fp-setup|keys ready|decryptor ready"
```

### Done Means Done

The app has real session crypto state and can attempt to decrypt mirror video packets.

---

# P0 — SETUP Response Correctness

## FMR-P0-005 — Replace hand-rolled binary plist SETUP response

**Priority:** P0
**Status:** TODO
**Area:** RTSP / plist

### Problem

`setup_response_bplist()` manually constructs a binary plist. This is fragile and may break macOS parsing.

### Required Change

Replace hand-written bplist with one of:

- validated XML plist for quick test, or
- proper plist builder / libplist / copied proven builder for serious implementation.

The response must include at minimum:

```text
eventPort
timingPort
streams
  - dataPort
  - type
  - encryptionType if confirmed required by capture/reference behavior
```

### Preferred MVP Values

```text
eventPort = 7000 or captured/reference value
timingPort = 7102
dataPort = 7100
type = 110
```

### Files Likely Affected

```text
app/src/main/cpp/airplay_server.cpp
```

### Acceptance Criteria

- No manual offset-table bplist construction remains unless fully validated.
- SETUP response body can be parsed by a plist parser.
- Content-Type matches body format.
- macOS does not disconnect immediately after SETUP because of malformed plist.

### Verification

```bash
./gradlew :app:assembleDebug
adb logcat | grep -E "SETUP|plist|dataPort|timingPort|eventPort"
```

### Done Means Done

SETUP response is boring, valid, and no longer a source of mystery failure.

---

# P0 — Mirror Packet Decryption Before Decode

## FMR-P0-006 — Add decrypt stage before type-0 NAL extraction

**Priority:** P0
**Status:** TODO
**Area:** Mirror TCP / Video pipeline

### Problem

Current type-0 mirror packets are treated as if payload is already readable H.264. That is wrong for real encrypted mirroring.

### Required Change

Change packet flow:

```text
read 128-byte mirror header
read encrypted payload
classify packet type
if type == 0:
    decrypt payload
    parse decrypted length-prefixed H.264 NALs
    convert to Annex-B
    queue to decoder
```

### Required Behavior

If decryptor is not ready:

```text
drop packet
log: "video payload received before decryptor ready"
```

If decryption fails:

```text
drop packet
log: "mirror video decrypt failed"
```

Do not feed encrypted bytes to MediaCodec.

### Files Likely Affected

```text
app/src/main/cpp/airplay_server.cpp
app/src/main/cpp/video_decoder_android.cpp
app/src/main/cpp/airplay_crypto/*
```

### Acceptance Criteria

- Encrypted payload is never passed to `queue_length_prefixed_nals`.
- Decrypted payload passes sanity checks before queueing.
- Logs show decrypted payload size and detected NAL types.
- Decoder only receives Annex-B NALs after decryption.

### Verification

```bash
adb logcat | grep -E "mirror packet|decrypt|NAL|IDR|SPS|PPS"
```

### Done Means Done

The code path becomes:

```text
encrypted mirror packet -> decrypted H.264 payload -> Annex-B NALs -> MediaCodec
```

---

# P0 — Type-1 Codec Data Parsing

## FMR-P0-007 — Replace raw SPS/PPS offset parsing with avcC parser

**Priority:** P0
**Status:** TODO
**Area:** H.264 / Mirror packet parsing

### Problem

`handle_mirror_sps_pps()` uses guessed byte offsets to extract SPS/PPS. Type-1 codec-data payload should be parsed as AVCDecoderConfigurationRecord / `avcC`.

### Required Change

Add:

```text
app/src/main/cpp/h264/
  h264_avcc_parser.h
  h264_avcc_parser.cpp
```

Implement:

```cpp
struct AvccParseResult {
    bool ok;
    std::vector<std::vector<uint8_t>> sps;
    std::vector<std::vector<uint8_t>> pps;
    std::string error;
};

AvccParseResult parse_avcc(
    const uint8_t* data,
    size_t len
);
```

### Required Behavior

For type-1 packet:

```text
decrypt if needed
parse avcC
extract SPS/PPS
store parameter sets
configure decoder when both are available
```

### Acceptance Criteria

- No hardcoded `payload + 6`, `payload + 8`, etc. offset parser remains for SPS/PPS.
- Parser validates length before every read.
- Parser supports multiple SPS/PPS entries.
- Parser logs SPS/PPS sizes but not huge binary dumps.

### Verification

```bash
adb logcat | grep -E "avcC|SPS|PPS|codec data"
```

### Done Means Done

Codec data is parsed according to structure, not vibes.

---

# P0 — Mirror Timestamp Normalization

## FMR-P0-008 — Fix mirror PTS conversion and normalize first frame to zero

**Priority:** P0
**Status:** TODO
**Area:** Timing / MediaCodec

### Problem

Current `mirror_pts_us()` reads bytes 8..15 and divides by 1000. That may produce invalid timestamps unless the raw value is already normalized exactly as assumed.

Bad PTS can cause MediaCodec stalls, frame rejection, or weird latency.

### Required Change

Implement a dedicated parser:

```cpp
struct MirrorTimestampState {
    bool initialized;
    uint64_t first_remote_ns;
};

int64_t mirror_pts_us(
    const uint8_t* header,
    MirrorTimestampState& state
);
```

Behavior:

```text
read timestamp from header bytes 8..15
convert using captured/reference AirPlay/UxPlay-compatible logic
normalize first video timestamp to 0
return monotonic microseconds for MediaCodec
```

### MVP Rule

For first frame, normalized monotonic PTS matters more than perfect wall-clock sync.

### Acceptance Criteria

- First decoded frame PTS is near `0`.
- Later PTS values are monotonic.
- No giant epoch-scale PTS values are sent to MediaCodec.
- Logs show raw timestamp and normalized PTS for first few packets.

### Verification

```bash
adb logcat | grep -E "raw pts|normalized pts|MediaCodec"
```

### Done Means Done

MediaCodec receives sane microsecond PTS values.

---

# P0 — First Real IDR Frame

## FMR-P0-009 — Detect and log first decrypted IDR frame

**Priority:** P0
**Status:** TODO
**Area:** H.264 / First-pixel proof

### Problem

Right now there is no hard proof that decrypted video is valid H.264.

### Required Change

After decryption and NAL conversion, inspect NAL types:

```text
1 = non-IDR slice
5 = IDR
6 = SEI
7 = SPS
8 = PPS
```

Add one-time logs:

```text
first_sps_seen
first_pps_seen
first_idr_seen
first_frame_queued
first_frame_render_attempted
```

### Acceptance Criteria

- Logs show first SPS.
- Logs show first PPS.
- Logs show first IDR.
- First IDR is queued after decoder configuration.
- TV shows at least a frozen macOS desktop frame.

### Verification

```bash
adb logcat | grep -E "first_sps|first_pps|first_idr|first_frame"
```

### Done Means Done

There is proof that real decrypted macOS H.264 reached the decoder.

---

# P1 — ANNOUNCE SDP Parsing

## FMR-P1-010 — Parse `sprop-parameter-sets` from ANNOUNCE SDP

**Priority:** P1
**Status:** TODO
**Area:** RTSP / SDP / H.264

### Problem

ANNOUNCE body is currently logged but not parsed. macOS may provide SPS/PPS there before mirror stream codec-data arrives.

### Required Change

Add parser for:

```text
a=sprop-parameter-sets=<base64-sps>,<base64-pps>
```

Then:

```text
base64 decode SPS/PPS
store parameter sets
make them available to decoder before first IDR
```

### Files Likely Affected

```text
app/src/main/cpp/airplay_server.cpp
app/src/main/cpp/h264/*
```

### Acceptance Criteria

- ANNOUNCE SDP is parsed safely.
- SPS/PPS from SDP can configure decoder.
- Binary SDP data is not logged unsafely.
- If SDP has no parameter sets, code continues waiting for type-1 codec data.

### Verification

```bash
adb logcat | grep -E "ANNOUNCE|sprop|SPS|PPS"
```

### Done Means Done

Decoder does not depend only on mirror type-1 packet for parameter sets.

---

# P1 — Decoder Parameter Set Rules

## FMR-P1-011 — Enforce SPS/PPS decoder rules

**Priority:** P1
**Status:** TODO
**Area:** MediaCodec / H.264

### Problem

Hardware decoders can behave badly if SPS/PPS are repeatedly fed as normal frames after codec configuration.

### Required Change

Rules:

```text
Before decoder configured:
  collect SPS/PPS

When both available:
  configure MediaCodec with csd-0/csd-1

After decoder configured:
  do not feed SPS/PPS as normal frames
  if SPS/PPS changes, flush/reconfigure intentionally
```

### Acceptance Criteria

- SPS/PPS are not queued as normal video frames after configure.
- If new SPS/PPS arrives, log it and either ignore or reconfigure explicitly.
- Decoder does not crash on repeated codec-data packets.

### Verification

```bash
adb logcat | grep -E "decoder configured|SPS changed|PPS changed"
```

### Done Means Done

Codec parameter handling is deliberate, not accidental.

---

# P1 — RTSP Parser Safety

## FMR-P1-012 — Add RTSP parser bounds and binary-safe logging

**Priority:** P1
**Status:** TODO
**Area:** RTSP / Security / Stability

### Problem

Current RTSP parsing is good enough for a spike but not safe enough for repeated testing.

### Required Change

Add limits:

```text
MAX_HEADER_BYTES = 32 KB
MAX_BODY_BYTES = 2 MB unless endpoint-specific
MAX_REQUEST_LINE_BYTES = 8 KB
```

Reject:

```text
negative Content-Length
invalid Content-Length
oversized body
malformed request line
missing CSeq where required
```

Logging rules:

```text
Do not log binary bodies as C strings.
For binary payloads, log size and first 16 bytes hex only.
```

### Acceptance Criteria

- Oversized RTSP request is rejected.
- Binary plist/body does not corrupt logs.
- Persistent connection still works for normal macOS request sequence.

### Verification

```bash
./gradlew :app:assembleDebug
adb logcat | grep -E "RTSP|Content-Length|reject|malformed"
```

### Done Means Done

Bad input does not crash or poison the session.

---

# P1 — Thread / Socket Cleanup

## FMR-P1-013 — Simplify video receiver shutdown

**Priority:** P1
**Status:** TODO
**Area:** Native lifecycle

### Problem

The current shutdown logic mostly works, but it has redundant conditions and socket shutdown can be cleaner.

### Required Change

Simplify:

```cpp
static void stop_video_receiver() {
    g_video_running = false;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_video_fd >= 0) {
            shutdown(g_video_fd, SHUT_RDWR);
        }
    }

    if (g_video_thread.joinable()) {
        g_video_thread.join();
    }
}
```

Also ensure accepted stream fd is closed inside the video thread.

### Acceptance Criteria

- Start/stop receiver 20 times.
- No native crash.
- No stuck thread.
- No port bind failure after restart.

### Verification

```bash
adb logcat | grep -E "receiver started|receiver stopped|bind failed|thread finished"
```

### Done Means Done

Receiver can restart repeatedly without wedging the native process.

---

# P1 — Build / CMake Integration

## FMR-P1-014 — Wire new native files into CMake

**Priority:** P1
**Status:** TODO
**Area:** Build system

### Problem

New crypto, plist, and H.264 parser files must be added cleanly to the native build.

### Required Change

Update:

```text
app/src/main/cpp/CMakeLists.txt
```

Add:

```text
airplay_crypto/*
h264/*
plist helper if used
crypto backend link if used
```

### Acceptance Criteria

- Debug build succeeds.
- Release build succeeds.
- No undefined native symbols.
- JNI still loads `fossmirror`.

### Verification

```bash
./gradlew clean :app:assembleDebug :app:assembleRelease
```

### Done Means Done

The new protocol stack builds consistently.

---

# P2 — Device Identity Hardening

## FMR-P2-015 — Generate stable device key instead of static PK

**Priority:** P2
**Status:** TODO after first frame
**Area:** Security / Device identity

### Problem

Static public key / device identity is acceptable for a spike but not production.

### Required Change

Generate device keypair on first run.

Store private key in:

```text
Android Keystore
```

Expose public key as AirPlay `pk`.

### Acceptance Criteria

- Device keeps same identity across app restarts.
- Reinstall resets identity unless backup support is intentionally added.
- Private key is not logged or exported.
- `pk` comes from generated key, not hardcoded constant.

### Done Means Done

The receiver has its own identity instead of pretending to be a copied reference device.

---

# P2 — Lifecycle Hardening After First Pixel

## FMR-P2-016 — Separate service lifetime from surface lifetime

**Priority:** P2
**Status:** TODO after first frame
**Area:** Android lifecycle / Decoder lifecycle

### Problem

Surface lifecycle and receiver/server lifecycle should not be the same thing.

### Required Change

Rules:

```text
ReceiverService owns AirPlay server lifetime.
Activity owns SurfaceView only.
surfaceCreated -> attach native surface.
surfaceDestroyed -> detach native surface.
Service stop -> stop RTSP/mirror/decode.
```

### Acceptance Criteria

- Press Home during receiver mode.
- Return to app.
- Server is not half-dead.
- Surface reattaches cleanly.
- No crash on orientation/activity recreation.

### Done Means Done

The receiver behaves like a TV receiver, not like a fragile demo activity.

---

# P2 — Network / mDNS Resilience

## FMR-P2-017 — Re-register Bonjour on network changes

**Priority:** P2
**Status:** TODO after first frame
**Area:** mDNS / Android networking

### Problem

JmDNS can flake across Wi-Fi changes, multicast lock changes, or sleep/wake.

### Required Change

On connectivity change:

```text
close old JmDNS
release old multicast lock
create new JmDNS bound to active interface
re-register _airplay._tcp
re-register _raop._tcp
```

### Acceptance Criteria

- Turn Wi-Fi off/on.
- Receiver reappears in macOS Screen Mirroring.
- No duplicate stale service registrations.
- Logs show unregister/register cycle.

### Done Means Done

Discovery survives normal Android TV network weirdness.

---

# Explicitly Out Of Scope Until First Frame

Do not work on these before FMR-P0-009 is done:

- Audio
- AV sync
- HEVC
- UI polish
- Settings screen
- Trusted device UX
- Multiple clients
- Stats overlay
- Fancy reconnect UX
- Release signing
- GitHub Actions
- App icon/banner polish
- Long-term security hardening
- Performance tuning beyond avoiding OOM/deadlock

---

# Corrected Critical Path

```text
1. Stop fake protocol success
2. Build crypto adapter seam
3. Integrate real pairing/FairPlay/session decrypt logic
4. Replace fragile SETUP plist
5. Decrypt mirror payload before parsing
6. Parse type-1 avcC codec data
7. Normalize mirror PTS
8. Detect first SPS/PPS/IDR
9. Feed first decrypted IDR to MediaCodec
10. See macOS frame on TV
```

Anything not on this chain is weekend waste.
