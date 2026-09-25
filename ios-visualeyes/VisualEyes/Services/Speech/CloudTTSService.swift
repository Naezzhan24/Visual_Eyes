import Foundation
import AVFoundation

/// "Assistant voice" cloud TTS — used for spoken UI prompts everywhere
/// except the material reader body. The reader deliberately needs a
/// *different*, live on-device synthesis engine instead (built in
/// Phase 4, `OnDeviceReaderTTSService`) because only a live session fires
/// word-boundary callbacks for karaoke highlighting; a downloaded MP3
/// clip like this one produces can't.
///
/// Calls the same `google-tts` Supabase Edge Function the Android app's
/// `GoogleTtsManager.java` already uses, unchanged — same request/response
/// shape, same 4 Neural2 voice IDs.
///
/// IMPORTANT: `speak(_:)` suspends until the audio has actually *finished
/// playing*, not just until it starts — callers (e.g. the assessment
/// flow) rely on this to know it's safe to start listening for an answer.
/// Gating "start listening" on a real completion signal rather than a
/// fixed delay after "playback started" is exactly the class of bug the
/// Android app's speech pipeline had to fix twice in its git history
/// (see the plan doc's bug-history notes) — don't regress this by making
/// `speak` fire-and-forget.
@MainActor
final class CloudTTSService: NSObject {
    static let shared = CloudTTSService()

    private var audioPlayer: AVAudioPlayer?
    private let fallbackSynthesizer = AVSpeechSynthesizer()
    private let session: URLSession
    private var playbackContinuation: CheckedContinuation<Void, Never>?
    /// Bumped by every speak/stop, so a superseded prompt never plays.
    private var generation = 0
    private var currentUtterance: AVSpeechUtterance?

    init(session: URLSession = .shared) {
        self.session = session
        super.init()
        fallbackSynthesizer.delegate = self
    }

    /// Speaks `text` using the given assistant voice and rate scale
    /// (1.0 = normal, matches the 0.70–1.30 range the app persists per
    /// account). Falls back to the on-device `AVSpeechSynthesizer` (no
    /// network) if the cloud call fails for any reason — same fallback
    /// behavior as `GoogleTtsManager.java`. Suspends until speech finishes.
    /// `respectsPreferences: false` is for screens that only work with
    /// voice (assessment, feedback, voice preview) — see `VoicePreferences`.
    func speak(
        _ text: String,
        voice: AssistantVoice = .default,
        rateScale: Double = 1.0,
        respectsPreferences: Bool = true
    ) async {
        guard !text.isEmpty else { return }
        if respectsPreferences && !VoicePreferences.isTtsEnabled { return }

        // One voice at a time: a new prompt cuts off the previous one
        // (like GoogleTtsManager stopping before each speak) instead of
        // talking over it.
        stop()
        let myGeneration = generation

        let data = try? await fetchAudio(text: text, voice: voice, rateScale: rateScale)
        // The download takes a moment; if anything stopped or replaced
        // this prompt meanwhile (e.g. the student left the screen), drop
        // it rather than playing it over whatever screen is showing now.
        guard myGeneration == generation else { return }

        if let data, let player = try? makePlayer(data: data) {
            audioPlayer = player
            await withCheckedContinuation { continuation in
                playbackContinuation = continuation
                player.play()
            }
        } else {
            await speakOnDevice(text: text, rateScale: rateScale)
        }
    }

    /// Stops whatever is currently playing (cloud or fallback), cancels
    /// any prompt still downloading, and resumes any caller waiting on
    /// `speak(_:)` immediately, so a screen that's navigating away
    /// doesn't hang.
    func stop() {
        generation += 1
        audioPlayer?.stop()
        audioPlayer = nil
        currentUtterance = nil
        if fallbackSynthesizer.isSpeaking {
            fallbackSynthesizer.stopSpeaking(at: .immediate)
        }
        resumePlaybackContinuation()
    }

    private func fetchAudio(text: String, voice: AssistantVoice, rateScale: Double) async throws -> Data {
        var request = URLRequest(url: Config.googleTtsFunctionURL)
        request.httpMethod = "POST"
        request.setValue(Config.supabaseAnonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(Config.supabaseAnonKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        // NOTE: Android's GoogleTtsManager builds this SSML via
        // NumberSpeechFormatter (spells out phone numbers/years/decades
        // correctly). That formatter is a pure-logic port scheduled
        // alongside the reader in Phase 4, where it's also needed for
        // highlight-offset math. For now this only does the minimal
        // escaping SSML requires.
        let body: [String: Any] = [
            "input": ["ssml": "<speak>\(Self.escapeForSSML(text))</speak>"],
            "voice": [
                "languageCode": "en-US",
                "name": voice.rawValue,
                "ssmlGender": voice.ssmlGender
            ],
            "audioConfig": [
                "audioEncoding": "MP3",
                "speakingRate": rateScale,
                "pitch": 0.0
            ]
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }

        struct TTSResponse: Decodable { let audioContent: String }
        let decoded = try JSONDecoder().decode(TTSResponse.self, from: data)
        guard let mp3Data = Data(base64Encoded: decoded.audioContent) else {
            throw URLError(.cannotDecodeContentData)
        }
        return mp3Data
    }

    private func makePlayer(data: Data) throws -> AVAudioPlayer {
        try AudioSessionCoordinator.prepareForPlayback()
        let player = try AVAudioPlayer(data: data)
        player.delegate = self
        return player
    }

    private func speakOnDevice(text: String, rateScale: Double) async {
        try? AudioSessionCoordinator.prepareForPlayback()
        let utterance = AVSpeechUtterance(string: text)
        // AVSpeechUtterance.rate is 0...1 (default ≈0.5), not a
        // 1.0-centered multiplier like the cloud voice's speakingRate —
        // scale around the platform default rather than using rateScale
        // as a literal rate value.
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * Float(rateScale)
        currentUtterance = utterance
        await withCheckedContinuation { continuation in
            playbackContinuation = continuation
            fallbackSynthesizer.speak(utterance)
        }
    }

    /// Idempotent — safe to call from both `stop()` and a delegate
    /// callback without double-resuming (the second call always finds
    /// `nil` and no-ops).
    fileprivate func resumePlaybackContinuation() {
        playbackContinuation?.resume()
        playbackContinuation = nil
    }

    nonisolated static func escapeForSSML(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }
}

extension CloudTTSService: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            // A player that was already replaced must not end the newer
            // prompt's wait early.
            guard player === audioPlayer else { return }
            audioPlayer = nil
            AudioSessionCoordinator.deactivate()
            resumePlaybackContinuation()
        }
    }
}

extension CloudTTSService: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            guard utterance === currentUtterance else { return }
            currentUtterance = nil
            AudioSessionCoordinator.deactivate()
            resumePlaybackContinuation()
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in
            // stop() already resumed the waiter; a late cancel callback
            // must not end the next prompt's wait.
            guard utterance === currentUtterance else { return }
            currentUtterance = nil
            resumePlaybackContinuation()
        }
    }
}
