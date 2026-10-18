Yes — your TODO is now outdated. It still talks like you’re stuck at `SESSION_KEYS_NOT_READY`, but you’re past that. Your current priority is **latency**, then **mirror audio**, then **security/productization**. Your attached TODO should be rewritten around the new state. 

Copy/paste this as the new TODO.

````md
# FossMirrorTV TODO — Current Working Roadmap

## Current Status

✅ macOS discovers receiver  
✅ `/info` shape works  
✅ `/fp-setup` works  
✅ Timing/key SETUP works  
✅ Stream SETUP works  
✅ Mirror TCP `7100` connects  
✅ avcC / SPS / PPS received  
✅ FairPlay-derived video decrypt works  
✅ Valid H.264 NALs reach decoder  
✅ Android Surface / MediaCodec renders video  
❌ Video is laggy / slow  
❌ Audio is not implemented; YouTube complains  
❌ No production security/pairing yet  

## Hard Rule

Do not touch AirPlay protocol, `/info`, feature flags, FairPlay, AES, streamConnectionID, or Surface lifecycle unless a regression proves they broke.

The project has crossed from “make AirPlay work” into “make real-time mirroring usable.”

---

# Sprint A — Video Latency Pass

## A0 — Freeze the working protocol path

**Status:** DONE / LOCKED  
**Priority:** P0

Do not change:

- mDNS TXT values
- `/info`
- `/fp-setup`
- TimingAndKeys SETUP
- stream SETUP
- streamConnectionID handling
- mirror AES-CTR derivation
- Surface ownership shell

### Done Means Done

Video continues to connect, decrypt, decode, and display before and after latency work.

---

## A1 — Kill packet-level logging

**Status:** TODO  
**Priority:** P0

Current packet logs are too noisy for real-time video.

Disable per-packet logs after the first 10–20 packets.

Keep only:

- first avcC
- first SPS/PPS
- first valid decrypted NAL
- first decoder configure
- first rendered frame
- once-per-second stats

### Add once/sec stats

```text
rxPacketsPerSec
rxBytesPerSec
decryptMsAvg
queueDepth
droppedFrames
inputNoBuffer
renderedFrames
recvWaitMsMax
````

### Acceptance Criteria

No continuous `mirror packet header`, `mirror type=0 decrypted`, or `nal_check` spam during normal playback.

---

## A2 — Remove 250ms receive stalls

**Status:** TODO
**Priority:** P0

The current receive path may block too long inside `recv_exact`.

AirPlay mirroring video is an H.264 TCP stream with a fixed 128-byte packet header, so receive latency directly becomes display latency. ([Open AirPlay][1])

### Required Change

For the accepted mirror TCP socket:

* Prefer blocking socket reads for the mirror stream, or
* reduce poll timeout from `250ms` to `1–5ms`
* never sleep 250ms while completing one packet

### Acceptance Criteria

```text
recvWaitMsMax < 10ms during normal playback
no visible 250ms stalls
```

---

## A3 — Queue access units, not individual NALs

**Status:** TODO
**Priority:** P0

Current queue model is too close to “NAL queue.” That can secretly buffer many frames.

### Required Change

Each AirPlay `type=0` payload should become one compressed access unit/frame packet:

```text
type=0 payload
  -> decrypt in-place
  -> parse all length-prefixed NALs
  -> convert all NALs into ONE Annex-B access-unit buffer
  -> queue ONE access unit
```

Do not push each NAL separately.

### Queue Limit

```text
maxQueuedAccessUnits = 2
```

If full:

```text
drop oldest access unit
push newest access unit
```

### Acceptance Criteria

Queue depth never grows beyond 2 access units.

---

## A4 — Drop stale frames aggressively

**Status:** TODO
**Priority:** P0

Mirroring must prefer “current” over “complete.” Late perfect video is trash.

### Required Behavior

If the decoder is behind:

```text
drop old queued frames
keep newest 1–2 frames
```

For first latency pass, do not over-engineer IDR-aware dropping. A simple latest-frame policy is acceptable.

### Acceptance Criteria

Moving the mouse or window on macOS should update quickly, even if some frames are skipped.

---

## A5 — Make MediaCodec input non-blocking

**Status:** TODO
**Priority:** P0

Do not let `AMediaCodec_dequeueInputBuffer(10ms)` stall the decoder loop repeatedly.

The NDK MediaCodec API exposes input/output queueing and output release; output buffers must be released with render enabled when rendering to a surface. ([Android Developers][2])

### Required Change

Use:

```text
dequeueInputBuffer timeout <= 1000us
```

If no input buffer:

```text
drop current access unit
inputNoBuffer++
continue
```

### Acceptance Criteria

No receive/decrypt thread should wait on decoder input availability.

---

## A6 — Drain output more aggressively

**Status:** TODO
**Priority:** P1

Current `drain_output(timeout=0)` may not pull rendered frames fast enough.

### Required Change

Before and after input:

```text
drain_output(short timeout)
release output immediately with render=true
```

No artificial sleeps. No waiting for PTS.

### Acceptance Criteria

`renderedFrames` increases steadily during mirroring.

---

## A7 — Keep sane synthetic PTS

**Status:** TODO
**Priority:** P1

Raw AirPlay PTS values have been garbage/huge in logs. Keep synthetic PTS for now.

Android codecs use microsecond presentation timestamps for input buffers, and MediaFormat exposes timing/rate-related codec knobs such as operating rate and low-latency mode. ([Android Developers][3])

### Required Change

Use:

```cpp
ptsUs = frameIndex++ * 33333; // 30fps
```

Or for lowest-latency experiment:

```cpp
ptsUs = 0;
```

### Acceptance Criteria

No giant PTS values enter MediaCodec.

---

## A8 — Keep low-latency codec hints

**Status:** TODO
**Priority:** P2

Android supports low-latency decode mode for real-time use cases when the platform/codec supports it. ([Android Open Source Project][4])

Keep trying:

```cpp
AMediaFormat_setInt32(format, "priority", 0);
AMediaFormat_setFloat(format, "operating-rate", 60.0f);
AMediaFormat_setInt32(format, "low-latency", 1);
```

### Acceptance Criteria

Codec still configures successfully. If a device ignores hints, continue gracefully.

---

# Sprint B — Mirror Audio Support

## B0 — Capture audio-related RTSP/RAOP flow

**Status:** TODO
**Priority:** P1 after Sprint A

YouTube reports audio failure because mirror audio is not implemented.

AirPlay screen mirroring sends H.264 video over TCP, while AAC-ELD audio is sent separately through AirTunes. ([Open AirPlay][1]) UxPlay also distinguishes AirPlay Mirror mode, which uses lossy AAC audio while mirroring, from AirPlay Audio-only mode, which uses ALAC. ([Debian Sources][5])

### Required Logging

Add temporary logs for:

```text
ANNOUNCE
SETUP audio
RECORD audio
SET_PARAMETER volume
FLUSH
audio transport
audio format
audio ports
audio codec
```

### Acceptance Criteria

We know exactly what macOS sends for audio during mirroring.

---

## B1 — Implement mirror-mode audio receiver ports

**Status:** TODO
**Priority:** P1

Do not implement full AirPlay Audio-only ALAC first.

Scope is mirror audio only:

```text
receive audio packets
decode AAC-ELD if sent
play PCM via AudioTrack
```

### Acceptance Criteria

macOS/YouTube no longer reports audio failure during screen mirroring.

---

## B2 — Basic AudioTrack output

**Status:** TODO
**Priority:** P1

Use Android AudioTrack for decoded PCM.

### First Target

```text
audio plays, even if sync is rough
```

### Later Target

```text
A/V sync within acceptable range
```

---

## B3 — A/V sync pass

**Status:** TODO
**Priority:** P2

Do this only after video latency is usable and audio plays.

### Required Work

```text
measure video latency
measure audio buffer depth
align audio to current video policy
drop/trim if audio drifts
```

---

# Sprint C — YouTube / Media App Behavior

## C0 — Determine whether YouTube uses mirror audio or media handoff

**Status:** TODO
**Priority:** P2

Important trap: some AirPlay clients may stop mirroring and send a media URL to the receiver instead of continuing screen mirroring. The unofficial AirPlay spec notes that when video playback starts, a standard AirPlay connection can be used to send the video URL and mirroring stops. ([Open AirPlay][1])

### Test Matrix

```text
1. macOS desktop + system sound
2. Safari/Chrome simple audio page
3. YouTube in browser
4. Apple TV / QuickTime / native video player
```

### Acceptance Criteria

We know whether YouTube failure is:

```text
missing mirror audio
or
missing AirPlay media playback/handoff support
```

---

# Sprint D — Stability / Productization

## D0 — One-client policy

**Status:** TODO
**Priority:** P2

Only one active sender should own:

```text
RTSP session
mirror TCP
decoder
surface
audio output
```

Reject or disconnect additional clients.

---

## D1 — Remove sensitive logs

**Status:** TODO
**Priority:** P1

No logs for:

```text
AES keys
IVs
FairPlay secrets
full encrypted/decrypted packet dumps
```

Allow only:

```text
sizes
state transitions
first-byte sanity checks without secrets
stats counters
```

---

## D2 — Reconnect cleanup

**Status:** TODO
**Priority:** P2

On TEARDOWN or disconnect:

```text
stop mirror TCP
stop timing/event sockets if needed
clear decoder queue
stop live decoder
release MediaCodec
release audio
reset stream state
keep app alive for next connection
```

---

## D3 — GPL/legal decision

**Status:** TODO
**Priority:** P2

UxPlay is the practical reference, but direct copying has licensing implications. Keep track of copied/adapted code.

---

# Sprint E — Security / Pairing

## E0 — LAN-only guard

**Status:** TODO
**Priority:** P2

For now:

```text
bind LAN only
do not expose outside VPN/LAN
```

---

## E1 — Real pairing / PIN mode

**Status:** TODO
**Priority:** P3

Do not implement until video and mirror audio are stable.

Scope later:

```text
/pair-pin-start
/pair-setup
/pair-verify
trusted clients
device identity
```

---

# Sprint F — Platform Expansion

## F0 — Android TV only until stable

**Status:** TODO
**Priority:** P3

Do not chase LG webOS yet.

LG webOS apps are web apps using HTML/CSS/JavaScript and webOS TV APIs, not a direct port of an Android NDK MediaCodec receiver.

### Realistic LG Paths Later

```text
A. Android box / homelab receiver, LG as viewer
B. LG webOS app as thin viewer/client
C. Native/rooted webOS service as research project
```

---

# Obsolete / Completed Items

Delete or archive these from old TODOs:

```text
wire TimingAndKeys decrypted key into type-0 decrypt path
fix SESSION_KEYS_NOT_READY
first decrypted video packet
first valid NAL
fix Surface already connected
first visible frame
expand /info body
fix immediate RECORD -> TEARDOWN
fix streamConnectionID fallback
```

These are no longer current blockers.

---

# Current One-Line Instruction for the Agent

Video works. Do not touch protocol or crypto. Make it real-time: remove 250ms receive stalls, stop packet spam, queue access units not NALs, cap queue at 2, drop stale frames, make MediaCodec input non-blocking, render output immediately. Then implement mirror audio.

```
::contentReference[oaicite:9]{index=9}
```

[1]: https://openairplay.github.io/airplay-spec/screen_mirroring/index.html?utm_source=chatgpt.com "Screen Mirroring - Unofficial AirPlay Specification"
[2]: https://developer.android.com/ndk/reference/group/media?utm_source=chatgpt.com "Media - NDK"
[3]: https://developer.android.com/reference/android/media/MediaFormat?utm_source=chatgpt.com "MediaFormat | API reference"
[4]: https://source.android.com/docs/core/media/low-latency-media?utm_source=chatgpt.com "Low-latency decoding in MediaCodec"
[5]: https://sources.debian.org/src/uxplay/1.62-1/README.md/?utm_source=chatgpt.com "File: README.md"
