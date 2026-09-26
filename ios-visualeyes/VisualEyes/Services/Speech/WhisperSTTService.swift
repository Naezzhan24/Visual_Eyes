import Foundation
import WhisperKit

/// Tier 3 of the STT cascade — OpenAI Whisper running on the phone via
/// WhisperKit (Core ML), fully offline. Takes the place of Android's Vosk
/// tier (Vosk has no official iOS build) and works the same way: it
/// re-reads the audio Tier 2 already captured instead of opening the mic
/// a third time (HybridSpeechManager.reuseAudioForVosk).
///
/// The model is `openai_whisper-base.en` (~146MB). It's bundled from
/// `WhisperModels/` when present (run `scripts/fetch-whisper-model.sh`
/// before building) so it works offline from the first launch; otherwise
/// WhisperKit downloads it once, the first time the phone is online.
@MainActor
final class WhisperSTTService {
    static let shared = WhisperSTTService()
    static let modelName = "openai_whisper-base.en"

    enum WhisperError: Error, LocalizedError {
        case modelUnavailable
        case noSpeech

        var errorDescription: String? {
            switch self {
            case .modelUnavailable: return "Offline speech recognition isn't ready yet."
            case .noSpeech: return "Didn't hear anything."
            }
        }
    }

    private var loadTask: Task<WhisperKit?, Never>?

    private init() {}

    /// `WhisperModels/openai_whisper-base.en` inside the app bundle.
    private var bundledModelFolder: URL? {
        guard let root = Bundle.main.url(forResource: "WhisperModels", withExtension: nil) else { return nil }
        let folder = root.appendingPathComponent(Self.modelName)
        return FileManager.default.fileExists(atPath: folder.path) ? folder : nil
    }

    /// Loads the model in the background so the first offline answer
    /// isn't slowed by it.
    func preload() {
        _ = loader()
    }

    private func loader() -> Task<WhisperKit?, Never> {
        if let loadTask { return loadTask }
        let bundled = bundledModelFolder
        let task = Task<WhisperKit?, Never>(priority: .utility) {
            let config: WhisperKitConfig
            if let bundled {
                config = WhisperKitConfig(
                    model: Self.modelName,
                    modelFolder: bundled.path,
                    tokenizerFolder: bundled,
                    verbose: false,
                    logLevel: .error,
                    prewarm: false,
                    load: true,
                    download: false
                )
            } else {
                config = WhisperKitConfig(
                    model: Self.modelName,
                    verbose: false,
                    logLevel: .error,
                    load: true,
                    download: true
                )
            }
            return try? await WhisperKit(config)
        }
        loadTask = task
        return task
    }

    /// Transcribes 16-bit mono PCM captured at `sampleRate`.
    func transcribe(pcm: Data, sampleRate: Double) async throws -> String {
        guard let kit = await loader().value else {
            // Let a later attempt retry (e.g. the download failed offline).
            loadTask = nil
            throw WhisperError.modelUnavailable
        }

        let samples = Self.floatSamples(fromInt16: pcm)
        let audio = Self.resample(samples, from: sampleRate, to: 16_000)
        guard audio.count > 1_600 else { throw WhisperError.noSpeech } // < 0.1s

        var options = DecodingOptions()
        options.language = "en"
        options.temperature = 0
        options.usePrefillPrompt = true
        options.detectLanguage = false
        options.skipSpecialTokens = true
        options.withoutTimestamps = true

        let results = try await kit.transcribe(audioArray: audio, decodeOptions: options)
        let text = Self.clean(results.map(\.text).joined(separator: " "))
        guard !text.isEmpty else { throw WhisperError.noSpeech }
        return text
    }

    /// Whisper marks silence or noise as "[BLANK_AUDIO]", "(music)" and
    /// the like; those mean nobody spoke.
    static func clean(_ raw: String) -> String {
        raw.replacingOccurrences(of: #"\[[^\]]*\]|\([^)]*\)|<\|[^|]*\|>"#, with: " ", options: .regularExpression)
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
    }

    static func floatSamples(fromInt16 data: Data) -> [Float] {
        data.withUnsafeBytes { raw -> [Float] in
            raw.bindMemory(to: Int16.self).map { Float($0) / Float(Int16.max) }
        }
    }

    /// Linear-interpolation resample; plenty for speech going into a
    /// model that works at 16kHz.
    static func resample(_ samples: [Float], from sourceRate: Double, to targetRate: Double) -> [Float] {
        guard sourceRate > 0, sourceRate != targetRate, samples.count > 1 else { return samples }
        let ratio = sourceRate / targetRate
        let outputCount = Int(Double(samples.count) / ratio)
        var output = [Float](repeating: 0, count: outputCount)
        for i in 0..<outputCount {
            let position = Double(i) * ratio
            let index = Int(position)
            let fraction = Float(position - Double(index))
            let next = min(index + 1, samples.count - 1)
            output[i] = samples[index] * (1 - fraction) + samples[next] * fraction
        }
        return output
    }
}
