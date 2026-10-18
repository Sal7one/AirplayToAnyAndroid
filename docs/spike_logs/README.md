# Capture Guide

Real client diagnostics from 2026-10-16 are summarized in
[Testing](../TESTING.md#current-mac-and-tv-investigation). A captured first Mac
IDR decoded as black; it is not evidence of successful desktop mirroring.
Expected log sequences alone are not evidence of a successful connection.

Keep raw captures outside source control: use `docs/spike_logs/private/` locally
or a separate private directory. Captures can contain screen/audio content,
identity information, and session material. Redact before sharing; do not log
private keys, shared secrets, decrypted keys, or full media payloads.

For each test, record the date, macOS version, Android/emulator version, receiver
revision, network arrangement, and whether video/audio actually appeared.

## Reference Run

Run a known-working UxPlay build first. Record its exact commit and command.
Capture discovery and protocol metadata from the same Mac/client configuration:

```bash
dns-sd -B _airplay._tcp local
dns-sd -B _raop._tcp local
dns-sd -L FossMirrorTV _airplay._tcp local
```

Each `dns-sd` command runs until interrupted. Use the reference receiver's name
for its lookup. Stop duplicate receivers while comparing discovery records.

Capture the RTSP ordering, plist field types, pairing request/response lengths,
FairPlay phases, initial key/timing SETUP, later stream SETUP, timing exchanges,
and mirror header metadata. Record codec configuration and first-IDR detection.
Use a narrowly filtered packet capture on the interface connecting the hosts;
avoid collecting unrelated LAN traffic. Raw captures remain private.

## FossMirrorTV Run

Use the logcat command in [Testing](../TESTING.md). Useful milestones are:

- `RTSP bound/listening`
- `pair_setup` / `pair_verify verified=true` if pairing is negotiated
- `fairplay exchange` followed by `session_keys_ready`
- `SETUP classify` with keys first and stream types later
- `mirror TCP socket bound/listening` before the corresponding RTSP 200
- `AirPlay mirror TCP client connected`
- `codec_data`, `first_idr_seen`, `first_frame_queued`
- `video output format`, `audio FLUSH ... mirrorPreserved=true`, and explicit
  `TEARDOWN stream type=...` instead of inferring stream identity from body size
- `first_audio_frame_queued` and audio decoder/track diagnostics

In a debuggable install, explicitly create the app-cache marker
`capture-next-mirror-idr` to save one decrypted IDR plus parameter sets as
`mirror-first-idr.h264`. The marker is consumed once. It can now be armed after
the sharing chooser as well as before connecting; capturing only the sender's
initial black IDR does not characterize later screen content. Pull it privately,
inspect locally, and remove the app-cache file when finished. This diagnostic
is disabled in non-debuggable installs and never records an entire session.

Log the first failing stage and exact error, not just "Mac connected". If an
assumption is not supported by code, a capture, or runtime output, write
`UNKNOWN - needs capture`. The final proof remains a real Mac desktop frame
and audible audio, not successful RTSP responses.
