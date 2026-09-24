import Foundation
import Observation

enum AssessmentPhase: Equatable {
    case notStarted
    /// Word is shown, question asked, waiting for a voice or button
    /// yes/no answer.
    case awaitingAnswer
    /// Voice "yes" was given — must now read the word aloud to confirm.
    case verifyingReadAloud
    /// Button "Yes" was tapped — must now type the word to confirm.
    case verifyingTyped
    case finished
}

/// Stand-in for TextSizeTestActivity.java: the 5-word/5-size reading
/// ladder. Scoring itself lives in `AssessmentScoring` (pure logic,
/// separately unit-tested); this view model is the state machine driving
/// the question → answer → verification flow.
@MainActor
@Observable
final class AssessmentViewModel {
    let isRetake: Bool

    private(set) var currentIndex = 0
    private(set) var assignedWords: [String] = []
    private(set) var phase: AssessmentPhase = .notStarted
    private(set) var isListening = false
    private(set) var statusMessage = ""
    private(set) var wasCancelled = false
    private(set) var summary: AssessmentSummary?
    var typedAnswer = ""
    var errorMessage: String?

    private var readablePerSize = [Int](repeating: 0, count: AssessmentScoring.textSizes.count)
    private var wordRead = [Bool](repeating: false, count: AssessmentScoring.textSizes.count)
    private var yesCount = 0
    private var noCount = 0

    private let cascade: SpeechCascadeSession
    private let tts: CloudTTSService
    private let micPermission: MicPermissionService
    private let assessmentRepository: AssessmentRepository
    private let sessionStore: SessionStore

    var currentWord: String? {
        guard currentIndex < assignedWords.count else { return nil }
        return assignedWords[currentIndex]
    }

    var currentSize: CGFloat {
        AssessmentScoring.textSizes[min(currentIndex, AssessmentScoring.textSizes.count - 1)]
    }

    var progressText: String {
        "Word \(min(currentIndex + 1, assignedWords.count)) of \(assignedWords.count)"
    }

    init(
        isRetake: Bool,
        cascade: SpeechCascadeSession = SpeechCascadeSession(),
        tts: CloudTTSService = .shared,
        micPermission: MicPermissionService = .shared,
        assessmentRepository: AssessmentRepository = AssessmentRepository(),
        sessionStore: SessionStore = .shared
    ) {
        self.isRetake = isRetake
        self.cascade = cascade
        self.tts = tts
        self.micPermission = micPermission
        self.assessmentRepository = assessmentRepository
        self.sessionStore = sessionStore
    }

    func start() async {
        guard phase == .notStarted else { return }
        let tiers = isRetake ? AssessmentScoring.retakeWordTiers : AssessmentScoring.wordTiers
        assignedWords = AssessmentScoring.pickWords(from: tiers)
        await askCurrentItem()
    }

    private func askCurrentItem() async {
        guard currentWord != nil else { return }
        phase = .awaitingAnswer
        statusMessage = "Can you read this word? Please say yes or no, or tap the button."
        await tts.speak(statusMessage)
        await listenForYesNo()
    }

    private func listenForYesNo() async {
        guard await micPermission.requestIfNeeded() else {
            // No mic access — the Yes/No buttons remain fully usable
            // regardless of listening state, so the assessment isn't
            // blocked, just not voice-driven for this student/device.
            return
        }
        isListening = true
        cascade.listen(contextualStrings: ["yes", "no", "repeat", "stop"]) { [weak self] event in
            guard let self else { return }
            switch event {
            case .partial:
                break
            case .final(let text):
                self.isListening = false
                self.handleSpokenAnswer(text)
            case .error, .timedOut:
                self.isListening = false
            }
        }
    }

    private func handleSpokenAnswer(_ text: String) {
        guard phase == .awaitingAnswer else { return }
        if YesNoMatcher.isRepeat(text) {
            Task { await askCurrentItem() }
            return
        }
        if YesNoMatcher.isStop(text) {
            cancel()
            return
        }
        if YesNoMatcher.isYes(text) {
            Task { await beginReadAloudPhase() }
        } else if YesNoMatcher.isNo(text) {
            recordAnswer(isYes: false, verifiedRead: false)
        }
        // Anything else (unclear speech): stays in .awaitingAnswer — the
        // student can try again by voice or fall back to the buttons.
    }

    /// Button "Yes" — verified by typing the word back, matching
    /// Android's split between voice-yes (read-aloud verified) and
    /// button-yes (typed verified).
    func tapYesButton() {
        guard phase == .awaitingAnswer else { return }
        cascade.stop()
        isListening = false
        Task { await beginTypedWordPhase() }
    }

    func tapNoButton() {
        guard phase == .awaitingAnswer else { return }
        cascade.stop()
        isListening = false
        recordAnswer(isYes: false, verifiedRead: false)
    }

    func cancel() {
        cascade.stop()
        isListening = false
        wasCancelled = true
        phase = .finished
    }

    private func beginReadAloudPhase() async {
        guard let word = currentWord else { return }
        phase = .verifyingReadAloud
        statusMessage = "Please read the word out loud now."
        await tts.speak(statusMessage)

        guard await micPermission.requestIfNeeded() else {
            // Can't verify by voice without mic access — count the "yes"
            // but don't credit it as a confirmed read.
            recordAnswer(isYes: true, verifiedRead: false)
            return
        }

        isListening = true
        cascade.listen(contextualStrings: [word]) { [weak self] event in
            guard let self, self.phase == .verifyingReadAloud else { return }
            switch event {
            case .partial(let text):
                if TextSimilarity.matches(candidate: text, target: word) {
                    self.isListening = false
                    self.cascade.stop()
                    self.recordAnswer(isYes: true, verifiedRead: true)
                }
            case .final(let text):
                self.isListening = false
                let matched = TextSimilarity.matches(candidate: text, target: word)
                self.recordAnswer(isYes: true, verifiedRead: matched)
            case .error, .timedOut:
                self.isListening = false
                self.recordAnswer(isYes: true, verifiedRead: false)
            }
        }
    }

    private func beginTypedWordPhase() async {
        phase = .verifyingTyped
        typedAnswer = ""
        statusMessage = "Please type the word you see, then submit."
        await tts.speak(statusMessage)
    }

    func submitTypedWord() {
        guard phase == .verifyingTyped, let word = currentWord else { return }
        let matched = TextSimilarity.matches(candidate: typedAnswer, target: word)
        recordAnswer(isYes: true, verifiedRead: matched)
    }

    private func recordAnswer(isYes: Bool, verifiedRead: Bool) {
        if isYes {
            yesCount += 1
            if verifiedRead {
                readablePerSize[currentIndex] += 1
                wordRead[currentIndex] = true
            }
        } else {
            noCount += 1
        }

        currentIndex += 1
        if currentIndex >= assignedWords.count {
            Task { await finishTest() }
        } else {
            Task { await askCurrentItem() }
        }
    }

    private func finishTest() async {
        cascade.stop()
        isListening = false
        phase = .finished

        let recommendedSize = AssessmentScoring.recommendedTextSize(readablePerSize: readablePerSize)
        let impairment = AssessmentScoring.impairmentLevel(yesCount: yesCount, total: assignedWords.count)

        let outcomes = assignedWords.enumerated().map { index, word in
            AssessmentWordOutcome(word: word, size: AssessmentScoring.textSizes[index], wasRead: wordRead[index])
        }

        summary = AssessmentSummary(
            outcomes: outcomes,
            impairmentLevel: impairment,
            recommendedSize: recommendedSize,
            yesCount: yesCount,
            totalItems: assignedWords.count,
            isRetake: isRetake
        )

        guard let token = sessionStore.sessionToken else { return }
        do {
            try await assessmentRepository.saveAssessment(
                sessionToken: token,
                impairmentLevel: impairment.rawValue,
                recommendedTextSize: Int(recommendedSize),
                yesCount: yesCount,
                noCount: noCount
            )
        } catch let error as SupabaseError {
            if case .sessionExpired = error {
                sessionStore.forceLogout()
            } else {
                errorMessage = error.localizedDescription
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
