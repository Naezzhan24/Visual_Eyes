import Foundation
import AVFoundation

/// Tier 2 of the STT cascade — the app captures raw PCM itself and posts
/// it to the `google-stt` Supabase Edge Function, exactly like
/// `GoogleSttManager.java` does on Android. Reached when Tier 1
/// (on-device/system recognizer) fails or is unavailable.
///
/// NOTE: this captures at the input node's *native* sample rate (usually
/// 44.1kHz or 48kHz) rather than resampling to 16kHz like Android does —
/// Google Cloud Speech accepts other rates via `sampleRateHertz`, so this
/// avoids writing a from-scratch resampling pipeline blind (no way to
/// compile-test it from here). If transcription accuracy on real audio
/// turns out to need true 16kHz capture, that's the first thing to
/// revisit — this is one of the least-verified pieces in this port and
/// should get real device testing early.
@MainActor
final class CloudSTTService: NSObject {
    static let shared = CloudSTTService()

    enum CloudSTTError: Error, LocalizedError {
        case audioEngineFailure(String)
        case noSpeechDetected
        case networkFailure(String)

        var errorDescription: String? {
            switch self {
            case .audioEngineFailure(let message): return "Audio error: \(message)"
            case .noSpeechDetected: return "Didn't hear anything."
            case .networkFailure(let message): return "Network error: \(message)"
            }
        }
    }

    private let audioEngine = AVAudioEngine()
    private var pcmBuffer = Data()
    private var isRecording = false

    private override init() {
        super.init()
    }

    /// Records until ~1.2s of trailing silence is detected (a
    /// hand-rolled RMS-threshold VAD, matching the "auto-stop ~1.2s
    /// after speech ends" behavior `GoogleSttManager.java` documents) or
    /// `maxDuration` elapses, then POSTs the captured audio to
    /// `google-stt` and returns the transcript.
    func recognizeOnce(
        contextPhrases: [String] = [],
        maxDuration: Duration = .seconds(8)
    ) async throws -> String {
        let (pcm, sampleRate) = try await capturePCM(maxDuration: maxDuration)
        guard !pcm.isEmpty else { throw CloudSTTError.noSpeechDetected }
        return try await transcribe(pcm: pcm, sampleRate: sampleRate, contextPhrases: contextPhrases)
    }

    private func capturePCM(maxDuration: Duration) async throws -> (data: Data, sampleRate: Double) {
        try AudioSessionCoordinator.prepareForRecording()
        pcmBuffer = Data()
        isRecording = true

        let inputNode = audioEngine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        let sampleRate = inputFormat.sampleRate
        let minimumCaptureSamples = Int(sampleRate * 0.5) // don't stop before at least 0.5s captured

        var silenceStreakMs = 0
        let silenceThreshold: Float = 0.015
        let requiredTrailingSilenceMs = 1200

        return try await withCheckedThrowingContinuation { continuation in
            var didResume = false
            func finish(_ result: Result<(Data, Double), Error>) {
                guard !didResume else { return }
                didResume = true
                self.stopCapture()
                switch result {
                case .success(let value): continuation.resume(returning: value)
                case .failure(let error): continuation.resume(throwing: error)
                }
            }

            inputNode.removeTap(onBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
                guard let self, let channelData = buffer.floatChannelData else { return }
                let frameLength = Int(buffer.frameLength)
                let samples = UnsafeBufferPointer(start: channelData[0], count: frameLength)

                var int16Samples = [Int16]()
                int16Samples.reserveCapacity(frameLength)
                var sumSquares: Float = 0
                for sample in samples {
                    let clamped = max(-1.0, min(1.0, sample))
                    int16Samples.append(Int16(clamped * Float(Int16.max)))
                    sumSquares += clamped * clamped
                }
                let bytes = int16Samples.withUnsafeBufferPointer { Data(buffer: $0) }
                self.pcmBuffer.append(bytes)

                let rms = frameLength > 0 ? sqrt(sumSquares / Float(frameLength)) : 0
                let bufferMs = Int(Double(frameLength) / sampleRate * 1000)
                if rms < silenceThreshold {
                    silenceStreakMs += bufferMs
                } else {
                    silenceStreakMs = 0
                }
                if self.pcmBuffer.count / 2 > minimumCaptureSamples && silenceStreakMs >= requiredTrailingSilenceMs {
                    finish(.success((self.pcmBuffer, sampleRate)))
                }
            }

            audioEngine.prepare()
            do {
                try audioEngine.start()
            } catch {
                finish(.failure(CloudSTTError.audioEngineFailure(String(describing: error))))
                return
            }

            Task {
                try? await Task.sleep(for: maxDuration)
                finish(.success((self.pcmBuffer, sampleRate)))
            }
        }
    }

    private func stopCapture() {
        guard isRecording else { return }
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        isRecording = false
        AudioSessionCoordinator.deactivate()
    }

    private func transcribe(pcm: Data, sampleRate: Double, contextPhrases: [String]) async throws -> String {
        var request = URLRequest(url: Config.googleSttFunctionURL)
        request.httpMethod = "POST"
        request.setValue(Config.supabaseAnonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(Config.supabaseAnonKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        var config: [String: Any] = [
            "encoding": "LINEAR16",
            "sampleRateHertz": Int(sampleRate),
            "languageCode": "en-PH",
            "alternativeLanguageCodes": ["fil-PH"],
            "enableAutomaticPunctuation": false,
            "model": "default"
        ]
        if !contextPhrases.isEmpty {
            config["speechContexts"] = [["phrases": contextPhrases]]
        }

        let body: [String: Any] = [
            "config": config,
            "audio": ["content": pcm.base64EncodedString()]
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw CloudSTTError.networkFailure("Bad response from google-stt.")
        }

        struct SpeechResponse: Decodable {
            struct ResultRow: Decodable {
                struct Alternative: Decodable { let transcript: String }
                let alternatives: [Alternative]
            }
            let results: [ResultRow]?
        }

        let decoded = try JSONDecoder().decode(SpeechResponse.self, from: data)
        guard let transcript = decoded.results?.first?.alternatives.first?.transcript else {
            throw CloudSTTError.noSpeechDetected
        }
        return transcript
    }
}
