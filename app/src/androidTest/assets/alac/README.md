# ALAC Fixtures

Generated locally with FFmpeg 9.0.1 by
`app/src/main/cpp/audio_alac/tests/generate_fixtures.rb`. No captured media or
third-party recordings. The generated samples and fixtures are provided under
Apache-2.0, like their generator.

All samples are signed PCM16, stereo, 44100 Hz, interleaved little-endian.
The `.pcm` files are the original independently generated samples, not output
from the Apple decoder under test. FFmpeg's independent decoder also reproduced
them exactly before the packet files were accepted.

| Stem | Samples/channel | Packet bytes | PCM bytes | Path |
| --- | ---: | ---: | ---: | --- |
| ramp4096 | 4096 | 535 | 16384 | Compressed predictor/Rice |
| ramp352 | 352 | 211 | 1408 | Compressed short frame |
| noise352 | 352 | 1416 | 1408 | Uncompressed escape short frame |

Ramp channel samples at index i are `(i*31 % 60001)-30000` and
`28000-(i*17 % 56001)`. Noise uses a deterministic 32-bit LCG seeded with
0x13579bdf, stepping once per channel sample, using its high 16 bits minus
32768. Container files are temporary; `.alac` holds exactly one raw ALAC
packet, including the frame-end element, without RTP, encryption or a cookie.
