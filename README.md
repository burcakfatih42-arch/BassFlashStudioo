# Bass Flash Studio Android prototype

Android app source. GitHub Actions builds a debug APK on push or manual workflow dispatch.

Features implemented in source:
- Audio-only file picker
- 720p / 1080p selection
- 60 fps black-to-white visual driven by a smoothed bass envelope
- Bass analysis uses a 35–150 Hz band-pass approximation
- H.264 MP4 video recording, AAC audio encoding, MediaMuxer output
- Saves final MP4 under Movies/BassFlashStudio

Important: This source has not been compiled or device-tested in this environment. Android MediaRecorder surface timing and device codec support can vary; treat first APK as a test build.
