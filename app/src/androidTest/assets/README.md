# AAC Playback Fixture

`aac-lc-44100-stereo.aac` is a generated 440 Hz sine wave, not recorded or
third-party audio. It exercises the real platform AAC-LC decoder and AudioTrack
in `AudioPlaybackRuntimeTest.realAacLcBurstsDrainDecoderOutputWithoutEvictingPcm`.

Generated with FFmpeg 9.0.1 (native AAC encoder):

```sh
ffmpeg -hide_banner -nostdin \
  -f lavfi -i sine=frequency=440:sample_rate=44100:duration=2 \
  -ac 2 -c:a aac -profile:a aac_low -b:a 128k -f adts \
  app/src/androidTest/assets/aac-lc-44100-stereo.aac
```

The ADTS stream contains 88 single-block frames, 1024 samples per frame, at
44100 Hz stereo (including encoder priming/padding). AudioSpecificConfig is
`12 10`. The test validates and removes each ADTS header before passing the raw
access unit to AudioPipeline; no fake decoder or capture permission is used.
Its initial six-packet burst is 139 ms, within the existing 160 ms PCM budget.
After the final enqueue, the real worker must finish draining and playing all
output, with every sample accounted for by playback or explicitly bounded
startup trimming. Per-second progress and no further trimming after the first
second of each continuous phase are checked separately.

SHA-256:
`355fdd2406a605728c5a2817cb4789c5c1716b6586fa80bd9037131a42d1f20a`

This fixture does not prove AAC-ELD support or audible correctness on TV hardware.
