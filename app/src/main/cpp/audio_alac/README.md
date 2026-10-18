# Bounded Apple ALAC Decoder

Apple's ALAC decoder, copyright (c) 2011 Apple Inc., Apache License 2.0.
The original copyright/license headers remain in every imported source file;
the license text is in `LICENSE`. New integration and hardening files use
Apache-2.0. This component imports no FairPlay code.

## Provenance

The thirteen files in `apple/` were individually retrieved, without running
reference code, from iMirror commit
`9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00`, directory
[`app/src/main/cpp/alac`](https://github.com/prat3ik/iMirror/tree/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/app/src/main/cpp/alac).
iMirror identifies the original source as
[macosforge/alac](https://github.com/macosforge/alac), Apple's 2011 ALAC release
(compatible bitstream version 0). Its vendored Apple commit is not identified;
the iMirror commit above is the reproducible source version for this import.
See its [NOTICE.md, lines 35-45](https://github.com/prat3ik/iMirror/blob/9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00/NOTICE.md#L35-L45).
The Apache-2.0 license copy was retrieved from PhairPlay commit
`ccfe04f391a59496405f1cead6909ac1cde380dd`, `LICENSE`.
The iMirror ARM/ARM64 endian detection change is retained and marked in
`apple/EndianPortable.c`. The reference's `shift.h` blob contained only
`404: Not Found`, was unused by the decoder, and was not imported.

## Contract And Local Changes (2026-10-16)

- Exactly 44100 Hz, two channels, 16-bit samples. Configured frame length is
  352..4096; a packet may contain a shorter final frame. Fixed AirPlay ALAC
  Rice parameters are pb=40, mb=10, kb=14, maxRun=255.
- Input packets are 1..65536 bytes. Output is at most frameLength * 2 * 2
  bytes of interleaved little-endian PCM16, with no partial output on failure.
- `ALACBitUtilities.c/.h`: sticky checked reads, peeks, advance, rewind and
  alignment. No input padding or speculative lookahead is required.
- `ag_dec.c`: Apple's Rice/Golomb decode algorithm uses the checked reader
  instead of raw 4/5-byte lookahead. Truncated codes and excessive zero runs
  fail. Loop counts are bounded by the validated sample count.
- `ALACDecoder.cpp`: validates the exact cookie before allocation and checks
  partial-frame lengths, complete channel count, predictor orders/modes,
  denominator shifts, mixing shifts, END, zero padding, and exact packet
  consumption. PCM16 shift-off bytes and unsupported elements are rejected.
  Malformed input cannot silently become zero-filled channel output.
- `dp_dec.c`: defines zero denominator rounding and unsigned left shifts.
  `-fwrapv` preserves Apple's intended wrapping predictor arithmetic.
- `bounded_alac.*` owns one decoder, checks configuration/packet/output sizes,
  serializes PCM explicitly as LE, and recreates state on flush or decode error.
- `alac_jni.cpp` uses tagged, never-reused registry IDs, not Java longs cast
  to pointers. Each instance has its own lock and uniquely owned decoder;
  registry entries remain alive while operations or concurrent closers wait.
  Close waits for in-flight work without holding the registry-wide lock.
  A maximum of 64 instances bounds leaks from callers that fail to close.
  Kotlin also synchronizes decode/flush/close and closes idempotently.
- Shared JNI helpers preserve pending Java exceptions, translate C++ failures,
  scope local references and bound array copies before allocation. Explicit
  `JNI_OnLoad` registration and a version script hide other native exports.

## Verification

`tests/generate_fixtures.rb` creates deterministic, independently specified
PCM and encodes three real single-packet fixtures with FFmpeg. It also decodes
each container with FFmpeg and checks exact equality with the source PCM.
The test assets are in `app/src/androidTest/assets/alac/`.

Standalone host checks (no Gradle or device access):

```sh
ruby app/src/main/cpp/audio_alac/tests/generate_fixtures.rb
cmake -S app/src/main/cpp/audio_alac -B /tmp/fossmirror-alac-host -G Ninja
cmake --build /tmp/fossmirror-alac-host
ctest --test-dir /tmp/fossmirror-alac-host --output-on-failure
```

Host checks default to AddressSanitizer and UndefinedBehaviorSanitizer and
exercise exact PCM, compressed and escape paths, every fixture truncation,
short/oversized declarations, output bounds, reset and deterministic mutations.
`NativeAlacDecoderTest` additionally exercises real JNI, independent instances,
idempotent close, and concurrent decode/flush/close on Android. Sanitizer tests
provide regression evidence, not a claim that all malformed bitstreams have
been exhaustively audited.

Validation on 2026-10-16: all three fixtures, every packet truncation,
configuration/output bounds, reset, and 12000 mutations passed both a plain
host build and Clang 22.1.8 with ASan+UBSan. Android arm64-v8a, armeabi-v7a,
x86 and x86_64 shared-library/JNI cross-builds passed using NDK 29, API 28. Android device
instrumentation is a separate check; a host pass does not establish Mac
session interoperability.

On this macOS 26.5 host, Apple Clang 17's ASan runtime deadlocked in
`AsanInitInternal` before `main` (confirmed by stack sampling). Use the installed
Homebrew Clang 22 for sanitizer checks:

```sh
cmake -S app/src/main/cpp/audio_alac -B /tmp/fossmirror-alac-host-llvm -G Ninja \
  -DCMAKE_C_COMPILER=/opt/homebrew/opt/llvm/bin/clang \
  -DCMAKE_CXX_COMPILER=/opt/homebrew/opt/llvm/bin/clang++
cmake --build /tmp/fossmirror-alac-host-llvm
ctest --test-dir /tmp/fossmirror-alac-host-llvm --output-on-failure
```
