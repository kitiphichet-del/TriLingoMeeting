# TriLingo Meeting v0.5

Clean rebuild intended to replace the earlier hand-crafted prototype APKs.

## What this build does
- Native Android Java UI
- Android SpeechRecognizer with partial and final transcripts
- Automatic Thai / Chinese / English language detection where the device supports it
- ML Kit language identification fallback
- ML Kit on-device translation for Thai / Chinese / English
- Conversation timeline
- One-tap Start, Pause/Resume, Stop
- Automatic recognition restart after each finalized utterance
- No raw audio recording

## Build
GitHub Actions builds `app-debug.apk` using the standard Android toolchain.

## Notes
This v0.5 stabilization build intentionally keeps the dependency graph small so the first priority is a valid installable APK and reliable microphone/transcription/translation flow.
