import Foundation

/// Orchestrates the STT tiers behind one stable interface every
/// voice-driven screen calls into, regardless of which tier actually
/// produced the result — the direct analogue of `SttCascadeSession.java`.
///
/// Tier 1 (on-device/system recognizer, forced on-device when offline) →
/// Tier 2 (custom capture sent to `google-stt`) → Tier 3 (Whisper on the
/// phone via WhisperKit, fed the audio Tier 2 already captured). With no
/// internet, Tier 2's upload is skipped and the capture goes straight to
/// Whisper.
///
/// The `generation` counter is the port of `voiceSessionId` from the
/// Android app: every `listen()`/`stop()` call bumps it, and every
/// in-flight callback checks it before touching caller-visible state.
/// This is what stops a stale callback from a superseded recognizer
/// barging into whatever phase came after it — the single most recurring
/// bug class documented in the Android app's speech-pipeline git history.
@MainActor
final class SpeechCascadeSession {
    enum Event {
        case partial(String)
        case final(String)
        case error(String)
        case timedOut
    }

    private(set) var generation = 0
    private let tier1: SpeechRecognitionService
    private let tier2: CloudSTTService
    private let tier3: WhisperSTTService

    init(
        tier1: SpeechRecognitionService = .shared,
        tier2: CloudSTTService = .shared,
        tier3: WhisperSTTService = .shared
    ) {
        self.tier1 = tier1
        self.tier2 = tier2
        self.tier3 = tier3
    }

    func listen(
        locale: Locale = Locale(identifier: "en-US"),
        contextualStrings: [String] = [],
        respectsPreferences: Bool = true,
        cloudFallbackOnSilence: Bool = true,
        onEvent: @escaping (Event) -> Void
    ) {
        generation += 1
        let myGeneration = generation

        if respectsPreferences && !VoicePreferences.isSttEnabled {
            onEvent(.error("Speech-to-Text is turned off. You can turn it on in Profile."))
            return
        }

        Task {
            do {
                let stream = tier1.recognize(locale: locale, contextualStrings: contextualStrings)
                var heardFinal = false
                for try await event in stream {
                    guard myGeneration == self.generation else { return }
                    switch event {
                    case .partial(let text): onEvent(.partial(text))
                    case .final(let text):
                        heardFinal = true
                        onEvent(.final(text))
                    }
                }
                // Tier 1 ended without a final result (silence, or it gave
                // up). Previously nothing was emitted here, leaving the
                // caller stuck on "Listening…" forever. Cascade to Tier 2,
                // like SttCascadeSession does on an empty result.
                if !heardFinal {
                    guard myGeneration == self.generation else { return }
                    guard cloudFallbackOnSilence else { onEvent(.timedOut); return }
                    await fallbackToTier2(contextPhrases: contextualStrings, generation: myGeneration, onEvent: onEvent)
                }
            } catch {
                guard myGeneration == self.generation else { return }
                // Always-on listeners (the tabs) pass false: nobody spoke,
                // so re-recording the silence for the cloud would just send
                // audio to Google every few seconds for nothing. Real
                // recognizer errors still cascade.
                if !cloudFallbackOnSilence, case SpeechRecognitionError.timedOut = error {
                    onEvent(.timedOut)
                    return
                }
                await fallbackToTier2(contextPhrases: contextualStrings, generation: myGeneration, onEvent: onEvent)
            }
        }
    }

    private func fallbackToTier2(
        contextPhrases: [String],
        generation myGeneration: Int,
        onEvent: @escaping (Event) -> Void
    ) async {
        guard myGeneration == self.generation else { return }

        let pcm: Data
        let sampleRate: Double
        do {
            (pcm, sampleRate) = try await tier2.capturePCM()
        } catch {
            guard myGeneration == self.generation else { return }
            onEvent(.error(error.localizedDescription))
            return
        }
        guard myGeneration == self.generation else { return }
        guard !pcm.isEmpty else {
            onEvent(.timedOut)
            return
        }

        if NetworkMonitor.shared.isOnline {
            do {
                let transcript = try await tier2.transcribe(pcm: pcm, sampleRate: sampleRate, contextPhrases: contextPhrases)
                guard myGeneration == self.generation else { return }
                onEvent(.final(transcript))
                return
            } catch CloudSTTService.CloudSTTError.noSpeechDetected {
                // Google heard the audio fine and found no words.
                guard myGeneration == self.generation else { return }
                onEvent(.timedOut)
                return
            } catch {
                // Network or server trouble: fall through to Whisper.
            }
        }

        // Tier 3: the same audio, transcribed on the phone.
        do {
            let transcript = try await tier3.transcribe(pcm: pcm, sampleRate: sampleRate)
            guard myGeneration == self.generation else { return }
            onEvent(.final(transcript))
        } catch WhisperSTTService.WhisperError.noSpeech {
            guard myGeneration == self.generation else { return }
            onEvent(.timedOut)
        } catch {
            guard myGeneration == self.generation else { return }
            onEvent(.error(error.localizedDescription))
        }
    }

    /// Bumps the generation (silencing any callback still in flight) and
    /// stops the underlying recognizer.
    func stop() {
        generation += 1
        tier1.stopListening()
    }
}
