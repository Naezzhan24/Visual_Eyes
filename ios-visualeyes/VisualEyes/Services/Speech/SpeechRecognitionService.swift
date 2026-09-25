import Foundation
import Speech
import AVFoundation

enum SpeechRecognitionError: Error, LocalizedError {
    case notAuthorized
    case recognizerUnavailable
    case audioEngineFailure(String)
    case timedOut

    var errorDescription: String? {
        switch self {
        case .notAuthorized: return "Microphone or speech recognition permission was denied."
        case .recognizerUnavailable: return "Speech recognition isn't available right now."
        case .audioEngineFailure(let message): return "Audio error: \(message)"
        case .timedOut: return "Didn't hear anything in time."
        }
    }
}

/// Tier 1 of the STT cascade — the on-device/system speech recognizer,
/// the direct analogue of Android's `android.speech.SpeechRecognizer`
/// tier inside `SttCascadeSession.java`.
///
/// Tier 2 (custom raw-PCM capture posted to the `google-stt` Edge
/// Function) and Tier 3 (Vosk offline) land in Phase 5 — this class is
/// deliberately scoped to Tier 1 only for now; `SpeechCascadeSession`
/// wraps it behind an interface that won't need to change when the other
/// tiers are added.
@MainActor
final class SpeechRecognitionService: NSObject {
    static let shared = SpeechRecognitionService()

    enum Event {
        case partial(String)
        case final(String)
    }

    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()

    private override init() {
        super.init()
    }

    /// Requests both the Speech-framework and microphone permissions.
    static func requestAuthorization() async -> Bool {
        let speechStatus = await withCheckedContinuation { (continuation: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status)
            }
        }
        guard speechStatus == .authorized else { return false }

        if #available(iOS 17.0, *) {
            return await AVAudioApplication.requestRecordPermission()
        } else {
            return await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        }
    }

    /// Starts a single listening attempt, watchdog-guarded so a
    /// recognizer that silently never calls back can't hang the caller
    /// forever — this mirrors why the Android cascade has a watchdog on
    /// every tier (a previously real bug: a never-firing callback froze
    /// the whole voice UI).
    ///
    /// Readiness is NOT gated behind a fixed delay before starting the
    /// audio engine — two of the most recent Android fixes were exactly
    /// about removing artificial delays in favor of using the actual
    /// "recognizer ready" signal, which here is simply "the audio engine
    /// started without throwing."
    func recognize(
        locale: Locale = Locale(identifier: "en-US"),
        contextualStrings: [String] = [],
        watchdog: Duration = .seconds(8)
    ) -> AsyncThrowingStream<Event, Error> {
        AsyncThrowingStream { continuation in
            guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
                continuation.finish(throwing: SpeechRecognitionError.recognizerUnavailable)
                return
            }

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.contextualStrings = contextualStrings
            // Prefer on-device when supported, but never force it
            // exclusively — replicates the Android lesson that forcing
            // offline-only STT (EXTRA_PREFER_OFFLINE=true) broke devices
            // without a downloaded language pack. `false` here means
            // "allow the recognizer to fall back to server-assisted
            // recognition," not "always use the network."
            request.requiresOnDeviceRecognition = false
            self.request = request

            do {
                try AudioSessionCoordinator.prepareForRecording()
                try self.startAudioEngine(into: request)
            } catch {
                self.request = nil
                continuation.finish(throwing: SpeechRecognitionError.audioEngineFailure(String(describing: error)))
                return
            }

            // SFSpeechRecognizer never ends a live-audio request on its own
            // when the speaker goes quiet, so a short "yes" stayed partial
            // until the watchdog threw it away as a timeout. Mirror
            // Android's EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS:
            // after 1.5s with no new partial, the last partial is final.
            let state = RecognitionState()

            func finishWithLastPartial(orThrow error: Error) {
                guard state.markFinished() else { return }
                if let last = state.lastPartial, !last.isEmpty {
                    continuation.yield(.final(last))
                    continuation.finish()
                } else {
                    continuation.finish(throwing: error)
                }
                Task { @MainActor in self.stopListening() }
            }

            let watchdogTask = Task {
                try? await Task.sleep(for: watchdog)
                guard !Task.isCancelled else { return }
                finishWithLastPartial(orThrow: SpeechRecognitionError.timedOut)
            }

            recognitionTask = recognizer.recognitionTask(with: request) { result, error in
                if let result {
                    let text = result.bestTranscription.formattedString
                    if result.isFinal {
                        guard state.markFinished() else { return }
                        watchdogTask.cancel()
                        state.cancelSilenceTimer()
                        continuation.yield(.final(text))
                        continuation.finish()
                        Task { @MainActor in self.stopListening() }
                        return
                    }
                    guard !state.isFinished else { return }
                    state.lastPartial = text
                    continuation.yield(.partial(text))
                    state.restartSilenceTimer(after: .milliseconds(1500)) {
                        watchdogTask.cancel()
                        finishWithLastPartial(orThrow: SpeechRecognitionError.timedOut)
                    }
                }
                if let error {
                    watchdogTask.cancel()
                    state.cancelSilenceTimer()
                    finishWithLastPartial(orThrow: error)
                }
            }

            continuation.onTermination = { [weak self] _ in
                watchdogTask.cancel()
                state.cancelSilenceTimer()
                Task { @MainActor in self?.stopListening() }
            }
        }
    }

    private func startAudioEngine(into request: SFSpeechAudioBufferRecognitionRequest) throws {
        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        inputNode.removeTap(onBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { buffer, _ in
            request.append(buffer)
        }
        audioEngine.prepare()
        try audioEngine.start()
    }

    func stopListening() {
        guard audioEngine.isRunning else { return }
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        recognitionTask?.cancel()
        recognitionTask = nil
        request = nil
        AudioSessionCoordinator.deactivate()
    }
}

/// Per-attempt bookkeeping for `recognize`: the recognizer's callback runs
/// off the main thread, so this is lock-guarded.
private final class RecognitionState: @unchecked Sendable {
    private let lock = NSLock()
    private var finished = false
    private var partial: String?
    private var silenceTask: Task<Void, Never>?

    var isFinished: Bool {
        lock.lock(); defer { lock.unlock() }
        return finished
    }

    var lastPartial: String? {
        get { lock.lock(); defer { lock.unlock() }; return partial }
        set { lock.lock(); partial = newValue; lock.unlock() }
    }

    /// Returns true only for the first caller, so the stream is finished once.
    func markFinished() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !finished else { return false }
        finished = true
        silenceTask?.cancel()
        return true
    }

    func restartSilenceTimer(after delay: Duration, onSilence: @escaping @Sendable () -> Void) {
        lock.lock(); defer { lock.unlock() }
        silenceTask?.cancel()
        silenceTask = Task {
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            onSilence()
        }
    }

    func cancelSilenceTimer() {
        lock.lock(); defer { lock.unlock() }
        silenceTask?.cancel()
    }
}
