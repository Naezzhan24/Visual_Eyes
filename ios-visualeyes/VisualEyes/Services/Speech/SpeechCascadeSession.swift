import Foundation

/// Orchestrates the STT tiers behind one stable interface every
/// voice-driven screen calls into, regardless of which tier actually
/// produced the result — the direct analogue of `SttCascadeSession.java`.
///
/// Tier 1 (on-device/system recognizer) → Tier 2 (custom cloud capture
/// via `google-stt`) → Tier 3 (Vosk offline — not yet functional, see
/// `VoskSTTService`). A voice screen that exhausts Tier 1 and Tier 2 with
/// no offline model wired up yet just times out, matching the honest
/// current state of the cascade rather than silently pretending offline
/// works.
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
    private let tier3: VoskSTTService

    init(
        tier1: SpeechRecognitionService = .shared,
        tier2: CloudSTTService = .shared,
        tier3: VoskSTTService = .shared
    ) {
        self.tier1 = tier1
        self.tier2 = tier2
        self.tier3 = tier3
    }

    func listen(
        locale: Locale = Locale(identifier: "en-US"),
        contextualStrings: [String] = [],
        onEvent: @escaping (Event) -> Void
    ) {
        generation += 1
        let myGeneration = generation

        Task {
            do {
                let stream = tier1.recognize(locale: locale, contextualStrings: contextualStrings)
                for try await event in stream {
                    guard myGeneration == self.generation else { return }
                    switch event {
                    case .partial(let text): onEvent(.partial(text))
                    case .final(let text): onEvent(.final(text))
                    }
                }
            } catch {
                guard myGeneration == self.generation else { return }
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
        do {
            let transcript = try await tier2.recognizeOnce(contextPhrases: contextPhrases)
            guard myGeneration == self.generation else { return }
            onEvent(.final(transcript))
        } catch {
            guard myGeneration == self.generation else { return }
            // Tier 3 (Vosk) plugs in right here once VoskSTTService is
            // wired up — reusing Tier 2's captured audio rather than
            // opening the mic a third time, matching
            // HybridSpeechManager.reuseAudioForVosk()'s approach.
            if let sttError = error as? CloudSTTService.CloudSTTError, case .noSpeechDetected = sttError {
                onEvent(.timedOut)
            } else {
                onEvent(.error(error.localizedDescription))
            }
        }
    }

    /// Bumps the generation (silencing any callback still in flight) and
    /// stops the underlying recognizer.
    func stop() {
        generation += 1
        tier1.stopListening()
    }
}
