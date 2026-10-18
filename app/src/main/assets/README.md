# Synthetic Decoder Fixture

`test_clip.h264` is a 48-frame, 1280x720 H.264 test-pattern clip. Every frame
was decoded and visually inspected during the 2026-10-18 publication audit;
no photographed/recorded screen content was visible. The original exact encoder
command was not retained, so byte-for-byte regeneration is not claimed.

It resembles FFmpeg's generated `testsrc` pattern, not downloaded film/video.
The runtime decoder tests use it as a surface/codec fixture, not as proof of
macOS compatibility. Other fixtures have generation commands beside their files
under `app/src/androidTest/assets/`.
