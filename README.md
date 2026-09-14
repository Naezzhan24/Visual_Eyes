# VisualED

An Android accessibility app that helps visually impaired students access their school's learning materials through voice — listen to materials read aloud, and register, navigate, and answer using speech instead of typing or reading small text.

## Problem

Students with visual impairments in Philippine schools often can't use standard learning-material apps: text is too small, there's no reliable screen-reader support, and low-connectivity areas make cloud-only speech tools unusable. VisualED is built specifically for this group, with the assumption that internet access is inconsistent and font/contrast needs vary per student.

## Core features

- **Accessibility-first onboarding** — an assessment during registration determines the student's visual impairment level and recommended text size, and the app adapts to it (`FontSizeManager`).
- **Voice-guided registration** — every field is read back for confirmation before it's accepted (spelled out letter-by-letter for names, "at"/"dot" read-back for emails), with a letter-by-letter correction flow when the recognizer mishears a name, and a spoken Privacy Policy agreement step before submitting (`RegisterActivity`).
- **Voice-first interaction** — registration, navigation, and reading can all be done by voice, not just tapped. Returning users are greeted with "Welcome back"; a fresh install gets a plain "Welcome" (`AuthManager.hasSeenHome`).
- **Hybrid speech engine** — combines the on-device Android recognizer, offline Vosk (real-time partial results), and cloud Whisper/Google STT, cascading between them in that order so voice commands keep working even with poor or no internet (`HybridSpeechManager`, `SttCascadeSession`, `SpeechEngineHealth`). Recognizer restart/retry timing (no-speech, engine-busy) is standardized across every voice screen.
- **Accessible material viewer** — reads PDFs and learning materials aloud, tracks read progress, and extracts page content for TTS (`AccessibleMaterialActivity`, `MaterialViewerActivity`, `PdfPageImageExtractor`, `MaterialReadTracker`). Read-aloud highlights the exact word being spoken (karaoke-style, via TTS word-boundary callbacks) and auto-scrolls to keep it on screen, falling back to a whole-paragraph highlight on devices/voices that don't report word boundaries.
- **Feedback loop** — students can rate and comment on materials so schools know what's working (`FeedbackActivity`).
- **Privacy Policy in-app** — readable and voice-narrated, since some users can't read a wall of text on their own (`PrivacyPolicyActivity`).

## Architecture

- **Client**: Android (Java), single module (`app/`)
- **Backend**: [Supabase](https://supabase.com) — session-token auth, database, storage, and edge functions for `google-stt`, `google-tts`, and `whisper-transcribe` (`supabase/functions/`)
- **Crash reporting**: Firebase Crashlytics + Analytics, gated on the presence of `google-services.json` so the project still builds without Firebase configured
- **On-device speech**: [Vosk](https://alphacephei.com/vosk/) for offline recognition
- **Speech fallback ladder**: built-in Android `SpeechRecognizer` → cloud Whisper/Google STT → offline Vosk, each screen falling through to the next rather than giving up after one failed engine (`SttCascadeSession`)

## Getting started

1. Clone the repo.
2. Add `keystore.properties` (local-only, gitignored) if you need signed release builds — debug builds work without it.
3. (Optional) Add `app/google-services.json` from the Firebase console to enable Crashlytics/Analytics.
4. `./gradlew assembleDebug`

## Status

Actively developed. Core registration, assessment, materials, voice, and feedback flows are implemented; automated test coverage is still minimal.
