# VisualED

An Android accessibility app that helps visually impaired students access their school's learning materials through voice — listen to materials read aloud, and register, navigate, and answer using speech instead of typing or reading small text.

## Problem

Students with visual impairments in Philippine schools often can't use standard learning-material apps: text is too small, there's no reliable screen-reader support, and low-connectivity areas make cloud-only speech tools unusable. VisualED is built specifically for this group, with the assumption that internet access is inconsistent and font/contrast needs vary per student.

## Core features

- **Accessibility-first onboarding** — an assessment during registration determines the student's visual impairment level and recommended text size, and the app adapts to it (`FontSizeManager`).
- **Voice-first interaction** — registration, navigation, and reading can all be done by voice, not just tapped.
- **Hybrid speech engine** — combines on-device Vosk (offline, real-time partial results) with cloud Whisper/Google STT and TTS, cascading between them (`HybridSpeechManager`, `SttCascadeSession`, `SpeechEngineHealth`). This means core voice interaction keeps working even with poor or no internet, which is common in the schools this app targets.
- **Accessible material viewer** — reads PDFs and learning materials aloud, tracks read progress, and extracts page content for TTS (`AccessibleMaterialActivity`, `MaterialViewerActivity`, `PdfPageImageExtractor`, `MaterialReadTracker`).
- **Feedback loop** — students can rate and comment on materials so schools know what's working (`FeedbackActivity`).
- **Privacy Policy in-app** — readable and voice-narrated, since some users can't read a wall of text on their own (`PrivacyPolicyActivity`).

## Architecture

- **Client**: Android (Java), single module (`app/`)
- **Backend**: [Supabase](https://supabase.com) — auth, database, storage, and edge functions for `google-stt`, `google-tts`, and `whisper-transcribe` (`supabase/functions/`)
- **Crash reporting**: Firebase Crashlytics + Analytics, gated on the presence of `google-services.json` so the project still builds without Firebase configured
- **On-device speech**: [Vosk](https://alphacephei.com/vosk/) for offline recognition

## Getting started

1. Clone the repo.
2. Add `keystore.properties` (local-only, gitignored) if you need signed release builds — debug builds work without it.
3. (Optional) Add `app/google-services.json` from the Firebase console to enable Crashlytics/Analytics.
4. `./gradlew assembleDebug`

## Status

Actively developed. Core registration, assessment, materials, voice, and feedback flows are implemented; automated test coverage is still minimal.
