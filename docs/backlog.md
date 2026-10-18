Yeah — those old backlogs are now **historical artifacts**, not active TODOs.

They were written when success meant “one decrypted frame.” Now you already have:

```text
✅ AirPlay discovery
✅ /info
✅ fp-setup
✅ TimingAndKeys
✅ stream SETUP
✅ mirror TCP 7100
✅ avcC / SPS / PPS
✅ AES decrypt
✅ valid H.264 NALs
✅ MediaCodec renders video
❌ latency is bad
❌ audio missing
```

So the old “Corrected Protocol Backlog” is mostly obsolete because it targets first-frame/protocol work. The v2 backlog is more structured, but still too greenfield and contains old assumptions like RTP/video demux work that does **not** match the working mirror TCP path you now have.  

AirPlay mirroring video is H.264 over a TCP stream with a 128-byte packet header, while mirroring audio is separate AAC-ELD through AirTunes/RAOP, so your active backlog should be organized around **real-time TCP video**, then **mirror audio**, not generic RTP video work. ([Open AirPlay][1])

## What to do with the old backlogs

### Archive / mark completed

Move these to `docs/archive/old-backlogs/` or mark them as `DONE / HISTORICAL`:

```text
FMR-P0-002 fake fp-setup / fake protocol success
FMR-P0-005 SETUP response correctness
FMR-P0-006 mirror packet decrypt before decode
FMR-P0-007 avcC parser
FMR-P0-008 first-frame PTS emergency
FMR-P0-009 first IDR / first visible frame
AIRM video first-frame path
Surface handoff first-pixel path
streamConnectionID fallback
SESSION_KEYS_NOT_READY
immediate RECORD -> TEARDOWN
/info expansion
```

### Keep but demote

These are still valid, but not current blockers:

```text
license audit
UxPlay as source of truth
RTSP parser hardening
reconnect cleanup
mDNS/network resilience
one-client policy
security / pairing / PIN
trusted devices
stats overlay
LG/webOS future notes
```

### Delete or rewrite

These are dangerous if future agents follow them blindly:

```text
video RTP/UDP demux as baseline
RTP FU-A video reassembly as current mirror path
ANNOUNCE SDP as required for current video path
full greenfield spike plan before continuing
“first frame” as current goal
“audio out of scope until first frame”
```

The active project has already disproved those as current priorities.

---

# FossMirrorTV Backlog v3 — Active Roadmap

Paste this as your new top-level backlog.

```md
# FossMirrorTV Backlog v3 — Active Roadmap

## Current Reality

FossMirrorTV now receives and renders macOS AirPlay mirror video on Android TV.

Working:
- mDNS / Bonjour discovery
- RTSP `/info`
- `/fp-setup`
- TimingAndKeys SETUP
- stream SETUP
- mirror TCP `7100`
- streamConnectionID parsing/reuse
- FairPlay-derived AES video decrypt
- type-1 avcC parsing
- SPS/PPS delivery
- valid decrypted H.264 NALs
- Android Surface / MediaCodec rendering

Current blockers:
- video latency / slow playback
- missing mirror audio; YouTube reports audio failure
- no production security/pairing
- weak reconnect/stability
- too much historical spike code/logging

Hard rule:
Do not touch AirPlay protocol, `/info`, feature flags, `/fp-setup`, AES decrypt, streamConnectionID, or Surface ownership unless a regression proves they broke.
```

---

## Sprint A — Video Latency

This is the current P0. Android’s `MediaFormat` supports real-time/operating-rate style hints such as priority and operating rate, and low-latency decode support exists where codecs/platforms expose it, but those hints are not enough if your own receive/queue/decode path is buffering stale frames. ([Android Developers][2])

```md
## A1 — Kill packet spam

Status: TODO  
Priority: P0

Disable per-packet logs after the first 10–20 packets.

Keep:
- first avcC
- first SPS/PPS
- first valid NAL
- decoder configured
- once-per-second stats

Stats:
- rxPacketsPerSec
- rxBytesPerSec
- decryptMsAvg
- queueDepth
- droppedFrames
- inputNoBuffer
- renderedFrames
- recvWaitMsMax

Acceptance:
No continuous `mirror packet header`, `mirror type=0 decrypted`, or `nal_check` spam during playback.
```

```md
## A2 — Remove receive stalls

Status: TODO  
Priority: P0

The mirror TCP socket must not wait up to 250ms inside `recv_exact`.

Change:
- use blocking socket for accepted mirror TCP stream, or
- reduce poll timeout to 1–5ms
- never sleep 250ms while completing one AirPlay video packet

Acceptance:
- recvWaitMsMax normally under 10ms
- no visible quarter-second stalls
```

```md
## A3 — Queue access units, not individual NALs

Status: TODO  
Priority: P0

Each type-0 AirPlay payload should become one compressed access unit.

Pipeline:
type-0 payload
-> decrypt in-place
-> parse all length-prefixed NALs
-> convert all NALs into one Annex-B access-unit buffer
-> queue one access unit

Do not push each NAL separately.

Queue cap:
maxQueuedAccessUnits = 2

If full:
drop oldest access unit
push newest

Acceptance:
Queue depth never exceeds 2 access units.
```

```md
## A4 — Drop stale frames aggressively

Status: TODO  
Priority: P0

Screen mirroring must prefer current frames over complete history.

Rule:
If queue is behind, drop old pending frames and keep newest 1–2 access units.

Acceptance:
Mouse/window movement on macOS updates quickly, even if some frames are skipped.
```

```md
## A5 — Make decoder input non-blocking

Status: TODO  
Priority: P0

Do not spin forever on `AMediaCodec_dequeueInputBuffer(10ms)`.

Change:
- timeout <= 1000us
- if no input buffer: drop current access unit
- increment `inputNoBuffer`
- continue

Acceptance:
Receive/decrypt path never waits on decoder input availability.
```

```md
## A6 — Drain output aggressively

Status: TODO  
Priority: P1

Before and after input:
- drain output
- release output immediately with render=true
- no artificial sleep
- no PTS pacing yet

Acceptance:
renderedFrames increases steadily.
```

```md
## A7 — Keep synthetic PTS for now

Status: TODO  
Priority: P1

Do not use raw AirPlay PTS yet.

Use:
ptsUs = frameIndex++ * 33333

Experimental:
ptsUs = 0 for immediate render test.

Acceptance:
No huge PTS values enter MediaCodec.
```

```md
## A8 — Reuse buffers / reduce allocations

Status: TODO  
Priority: P1

Avoid per-packet heap churn.

Change:
- reuse receive buffer
- decrypt in-place
- reuse Annex-B scratch buffer
- copy only once into MediaCodec input buffer

Acceptance:
Lower CPU/GC pressure and smoother playback.
```

---

## Sprint B — Mirror Audio

Audio is now real scope. UxPlay distinguishes mirror mode audio from AirPlay Audio-only mode: mirror mode uses lossy AAC audio while mirroring, while audio-only mode uses ALAC. ([Debian Sources][3])

```md
## B1 — Capture audio flow

Status: TODO after latency pass  
Priority: P1

YouTube reports audio failure because mirror audio is not implemented.

Log:
- ANNOUNCE
- audio SETUP
- audio RECORD
- SET_PARAMETER volume
- FLUSH
- audio transport
- codec
- ports
- format fields

Acceptance:
We know exactly what macOS sends for mirror audio.
```

```md
## B2 — Implement mirror audio receiver

Status: TODO  
Priority: P1

Scope:
- mirror-mode audio only
- not full AirPlay Audio-only ALAC yet

Likely path:
- receive RAOP/AirTunes audio packets
- decode AAC-ELD if sent
- play PCM through Android AudioTrack / AAudio

Acceptance:
macOS/YouTube no longer reports audio failure during screen mirroring.
```

```md
## B3 — Basic AV sync

Status: TODO  
Priority: P2

Only after:
- video latency is acceptable
- audio plays

Acceptance:
Rough sync first. Perfect sync later.
```

---

## Sprint C — YouTube / Media App Behavior

```md
## C1 — Determine YouTube mode

Status: TODO  
Priority: P2

Test:
1. macOS desktop + system audio
2. browser simple audio
3. YouTube in browser
4. native video player / QuickTime / Apple TV app

Goal:
Identify whether YouTube failure is:
- missing mirror audio
- or AirPlay media URL handoff

Note:
Some AirPlay clients may stop mirroring and send a media URL instead.
```

---

## Sprint D — Stability / Cleanup

```md
## D1 — One-client policy

Status: TODO  
Priority: P2

Only one sender owns:
- RTSP session
- mirror TCP
- decoder
- surface
- future audio output

Reject or disconnect additional clients.
```

```md
## D2 — Remove sensitive logs

Status: TODO  
Priority: P1

Remove:
- AES key logs
- IV logs
- FairPlay secrets
- full packet dumps

Keep:
- sizes
- state transitions
- counters
- first-byte sanity checks only when needed
```

```md
## D3 — Reconnect cleanup

Status: TODO  
Priority: P2

On TEARDOWN/disconnect:
- stop mirror TCP
- clear decoder queue
- stop live decoder
- release codec
- release audio later
- reset stream state
- keep app alive for next connection

Acceptance:
10 connect/disconnect cycles without process restart.
```

```md
## D4 — RTSP parser hardening

Status: TODO  
Priority: P2

Add:
- max header bytes
- max body bytes
- malformed request rejection
- binary-safe logging

Do this after latency/audio, unless fuzzing/crashes appear.
```

---

## Sprint E — Security / Pairing

```md
## E1 — LAN-only guard

Status: TODO  
Priority: P2

Do not expose receiver outside LAN/VPN.

Reject non-private remote IPs unless explicitly allowed.
```

```md
## E2 — Real pairing / PIN

Status: TODO  
Priority: P3

Do not implement until video and mirror audio are stable.

Scope later:
- /pair-pin-start
- /pair-setup
- /pair-verify
- trusted clients
- Android Keystore identity
```

---

## Sprint F — Platform Expansion

```md
## F1 — Android TV first

Status: TODO  
Priority: P3

Do not chase LG webOS yet.

LG webOS path later:
A. Android box/homelab receiver, LG as viewer
B. LG webOS thin viewer
C. rooted/native webOS service as research project
```

---

## Obsolete / Completed Items

```md
Archive these:

- wire decrypted key into type-0 path
- SESSION_KEYS_NOT_READY
- first decrypted packet
- first valid NAL
- first visible frame
- Surface already connected
- /info expansion
- immediate RECORD -> TEARDOWN
- streamConnectionID fallback
- avcC parser
- basic SurfaceView shell
```

## Current one-line instruction

Video works. Make it real-time: remove receive stalls, stop packet spam, queue access units not NALs, cap queue at 2, drop stale frames, make MediaCodec input non-blocking, render immediately. Then implement mirror audio.

````

## Important correction to the v2 backlog

The v2 backlog has useful structure and license discipline, but it is now misleading as an active implementation order. The “Reality Spike first, no production code” flow is obsolete because you already have a working spike-turned-prototype. The “RTP stream receiver” item should **not** be treated as the current video path. Keep it only if later captures prove a separate audio/RTP need. :contentReference[oaicite:5]{index=5}

Your new active file should probably be:

```text
BACKLOG_ACTIVE.md
````

and the old ones should become:

```text
docs/archive/BACKLOG_PROTOCOL_FIRST_FRAME.md
docs/archive/BACKLOG_GREENFIELD_V2.md
```

[1]: https://openairplay.github.io/airplay-spec/screen_mirroring/index.html?utm_source=chatgpt.com "Screen Mirroring - Unofficial AirPlay Specification"
[2]: https://developer.android.com/reference/android/media/MediaFormat?utm_source=chatgpt.com "MediaFormat | API reference"
[3]: https://sources.debian.org/src/uxplay/1.62-1/README.md/?utm_source=chatgpt.com "File: README.md"
