# Black-To-Color Regression Fixture

`black-to-color.h264` is generated test imagery, not a captured user screen.
It contains 20 AVC frames: a black intro followed by a moving color pattern.
Only the first frame is IDR. Access-unit delimiters preserve frame boundaries.

Generated using ffmpeg with libx264:

```bash
ffmpeg -f lavfi -i color=c=black:s=320x180:r=10:d=0.3 \
  -f lavfi -i testsrc2=s=320x180:r=10:d=1.7 \
  -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' -an \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p \
  -x264-params aud=1:keyint=300:min-keyint=300:scenecut=0:bframes=0 \
  -f h264 black-to-color.h264
```

The instrumentation test sends it through production RTSP SETUP, native
FairPlay key derivation using generated framing vectors, continuous encrypted
mirror TCP, AVC parsing, MediaCodec and the activity SurfaceView. It sends an
audio FLUSH after the black IDR and requires colored dependent frames to remain
decodable without another IDR. This does not substitute for a macOS/TV test.
