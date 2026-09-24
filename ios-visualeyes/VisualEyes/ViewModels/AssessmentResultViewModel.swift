import Foundation
import Observation

/// Stand-in for AssessmentResultActivity.java: speaks the same summary
/// sentence (support level, recommended size, N-out-of-M words read),
/// listens for "repeat"/"continue", and auto-continues after 4 seconds —
/// matching `AUTO_CONTINUE_DELAY_MS` exactly.
@MainActor
@Observable
final class AssessmentResultViewModel {
    let summary: AssessmentSummary
    private(set) var isListening = false

    private let tts: CloudTTSService
    private let cascade: SpeechCascadeSession
    private let micPermission: MicPermissionService
    private var autoContinueTask: Task<Void, Never>?
    private var didFinish = false

    var onContinue: (() -> Void)?

    init(
        summary: AssessmentSummary,
        tts: CloudTTSService = .shared,
        cascade: SpeechCascadeSession = SpeechCascadeSession(),
        micPermission: MicPermissionService = .shared
    ) {
        self.summary = summary
        self.tts = tts
        self.cascade = cascade
        self.micPermission = micPermission
    }

    var sizeSpokenText: String { "\(Int(summary.recommendedSize))" }

    func speakAndListen() async {
        autoContinueTask?.cancel()

        let voiceAvailable = await micPermission.requestIfNeeded()
        let tail = voiceAvailable
            ? "We will continue automatically in a few seconds. Say repeat to hear this again, or tap Continue now."
            : "We will continue automatically in a few seconds, or tap Continue now."
        let message = "Your visual impairment support level is \(summary.impairmentLevel.rawValue). "
            + "Recommended text size is \(sizeSpokenText). "
            + "You could clearly read \(summary.yesCount) out of \(summary.totalItems) words. "
            + tail

        await tts.speak(message)
        guard !didFinish else { return }

        if voiceAvailable {
            listenForCommand()
        }
        scheduleAutoContinue()
    }

    private func listenForCommand() {
        isListening = true
        cascade.listen(contextualStrings: ["repeat", "continue"]) { [weak self] event in
            guard let self else { return }
            switch event {
            case .partial:
                break
            case .final(let text):
                self.isListening = false
                if YesNoMatcher.isRepeat(text) {
                    self.autoContinueTask?.cancel()
                    Task { await self.speakAndListen() }
                } else if text.lowercased().contains("continue") {
                    self.finish()
                }
            case .error, .timedOut:
                self.isListening = false
            }
        }
    }

    private func scheduleAutoContinue() {
        autoContinueTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            finish()
        }
    }

    func finish() {
        guard !didFinish else { return }
        didFinish = true
        autoContinueTask?.cancel()
        cascade.stop()
        onContinue?()
    }
}
