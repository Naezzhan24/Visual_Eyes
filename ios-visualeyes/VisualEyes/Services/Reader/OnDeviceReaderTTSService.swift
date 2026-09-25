import Foundation
import AVFoundation

/// Reader-only on-device TTS — deliberately NOT routed through
/// `CloudTTSService`, for the same reason as Android: only a *live*
/// synthesis session fires word-boundary callbacks for karaoke
/// highlighting; a downloaded MP3 clip can't. Direct analogue of the
/// inline `android.speech.tts.TextToSpeech` +
/// `UtteranceProgressListener.onRangeStart` usage in
/// AccessibleMaterialActivity.java.
///
/// Unlike Android — which needs `NumberSpeechFormatter` to build SSML
/// and then map spoken-string offsets back to the *displayed* text,
/// because its Cloud/SSML path changes the text before speaking it —
/// this service speaks each chunk's original text directly.
/// `willSpeakRangeOfSpeechString` therefore already gives ranges straight
/// into the same text shown on screen; no offset-mapping layer is
/// needed. Revisit only if number/abbreviation pronunciation quality
/// genuinely requires preprocessing the text before speaking.
@MainActor
final class OnDeviceReaderTTSService: NSObject {
    struct WordRange {
        let range: NSRange
        let utteranceText: String
    }

    private let synthesizer = AVSpeechSynthesizer()
    private var onWordBoundary: ((WordRange) -> Void)?
    private var onFinished: (() -> Void)?
    private(set) var isSpeaking = false
    /// Only this utterance's callbacks count; a stopped one must not
    /// highlight or finish the next.
    private var currentUtterance: AVSpeechUtterance?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func speak(
        _ text: String,
        rate: Float = AVSpeechUtteranceDefaultSpeechRate,
        voiceGender: AVSpeechSynthesisVoiceGender? = nil,
        onWordBoundary: @escaping (WordRange) -> Void,
        onFinished: @escaping () -> Void
    ) {
        stop()
        self.onWordBoundary = onWordBoundary
        self.onFinished = onFinished

        try? AudioSessionCoordinator.prepareForPlayback()
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = rate
        if let voiceGender, let voice = Self.voice(forGender: voiceGender) {
            utterance.voice = voice
        }
        isSpeaking = true
        currentUtterance = utterance
        synthesizer.speak(utterance)
    }

    func stop() {
        currentUtterance = nil
        guard isSpeaking else { return }
        synthesizer.stopSpeaking(at: .immediate)
        isSpeaking = false
    }

    func pause() {
        synthesizer.pauseSpeaking(at: .word)
    }

    func resume() {
        synthesizer.continueSpeaking()
    }

    /// Picks the first installed en-US voice matching the requested
    /// gender. iOS exposes `.gender` directly (iOS 13+) — no need for
    /// Android's pitch-autocorrelation heuristic (`DeviceVoiceGuide.java`)
    /// to guess it.
    private static func voice(forGender gender: AVSpeechSynthesisVoiceGender) -> AVSpeechSynthesisVoice? {
        AVSpeechSynthesisVoice.speechVoices()
            .first { $0.language.hasPrefix("en") && $0.gender == gender }
    }
}

extension OnDeviceReaderTTSService: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        willSpeakRangeOfSpeechString characterRange: NSRange,
        utterance: AVSpeechUtterance
    ) {
        Task { @MainActor in
            guard utterance === currentUtterance else { return }
            onWordBoundary?(WordRange(range: characterRange, utteranceText: utterance.speechString))
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            guard utterance === currentUtterance else { return }
            currentUtterance = nil
            isSpeaking = false
            AudioSessionCoordinator.deactivate()
            onFinished?()
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in
            isSpeaking = false
        }
    }
}
