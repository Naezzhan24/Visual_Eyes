import Foundation
import Observation

enum AssessmentPhase: Equatable {
    /// First word shown; Start not pressed yet (it auto-starts shortly).
    case notStarted
    /// Speaking the welcome or the question for the current word.
    case asking
    /// Question asked; listening for a spoken yes/no, buttons enabled.
    case awaitingAnswer
    /// Voice "yes" — the student must now read the word aloud.
    case verifyingReadAloud
    /// Button "Yes" — the student must now type the word.
    case verifyingTyped
    /// Answer recorded; about to move to the next word.
    case answered
    /// "Stop" said; Start can begin again with new words.
    case stopped
    /// Last word answered; speaking the wrap-up and saving.
    case completing
    case finished
}

/// Port of TextSizeTestActivity.java: the 5-word/5-size reading ladder.
/// Scoring lives in `AssessmentScoring`; this is the state machine for
/// start → question → answer → verification → next word → save.
///
/// Every async step captures `flow` and bails out if it changed, which is
/// the port of Android's `voiceSessionId`/`readAloudAttemptId` guards: a
/// stale TTS completion or recognizer callback from a superseded step must
/// never act on the current one.
@MainActor
@Observable
final class AssessmentViewModel {
    let isRetake: Bool

    private(set) var currentIndex = 0
    private(set) var assignedWords: [String]
    private(set) var phase: AssessmentPhase = .notStarted
    private(set) var isListening = false
    private(set) var statusMessage: String
    private(set) var wasCancelled = false
    private(set) var summary: AssessmentSummary?
    var typedAnswer = ""
    var errorMessage: String?

    private var readablePerSize = [Int](repeating: 0, count: AssessmentScoring.textSizes.count)
    private var wordRead = [Bool](repeating: false, count: AssessmentScoring.textSizes.count)
    private var yesCount = 0
    private var noCount = 0
    private var retryCount = 0
    private var micAvailable = false
    private var flow = 0

    private static let maxRetry = 3
    private static let readAloudBudget: Duration = .seconds(15)
    private static let readAloudAttemptTimeout: Duration = .seconds(6)
    /// Spoken whenever the student has to fall back to tapping.
    private static let buttonColorHint = "The blue button is Yes, and the yellow button is No."

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
        "Size \(min(currentIndex + 1, assignedWords.count)) of \(assignedWords.count)"
    }

    var isRunning: Bool {
        switch phase {
        case .asking, .awaitingAnswer, .verifyingReadAloud, .verifyingTyped, .answered: return true
        default: return false
        }
    }

    /// Yes/No stay tappable for the whole test, like btnYes/btnNo, except
    /// while typing (Submit is the action then) or between words.
    var canAnswerWithButtons: Bool {
        phase == .asking || phase == .awaitingAnswer || phase == .verifyingReadAloud
    }

    var canStart: Bool { phase == .notStarted || phase == .stopped }

    var startButtonTitle: String {
        switch phase {
        case .notStarted: return "Start Voice Recognition"
        case .stopped: return "Start Assessment"
        default: return "Assessment Started"
        }
    }

    init(
        isRetake: Bool,
        cascade: SpeechCascadeSession? = nil,
        tts: CloudTTSService = .shared,
        micPermission: MicPermissionService = .shared,
        assessmentRepository: AssessmentRepository = AssessmentRepository(),
        sessionStore: SessionStore = .shared
    ) {
        self.isRetake = isRetake
        self.cascade = cascade ?? SpeechCascadeSession()
        self.tts = tts
        self.micPermission = micPermission
        self.assessmentRepository = assessmentRepository
        self.sessionStore = sessionStore
        // Words are picked up front so the first one is on screen right
        // away (showCurrentItem(false) in onCreate).
        assignedWords = AssessmentScoring.pickWords(
            from: isRetake ? AssessmentScoring.retakeWordTiers : AssessmentScoring.wordTiers
        )
        statusMessage = isRetake ? "Press Start to begin retake assessment." : "Press Start to begin."
    }

    // MARK: - Starting

    /// onCreate's delayed auto-start: ask for the mic, then begin whether
    /// or not it was granted (the buttons always work).
    func autoStart() async {
        try? await Task.sleep(for: .milliseconds(900))
        guard phase == .notStarted else { return }
        await start()
    }

    func start() async {
        guard canStart else { return }
        let restarting = phase == .stopped
        flow += 1
        let token = flow
        phase = .asking

        statusMessage = "Requesting microphone access..."
        micAvailable = await micPermission.requestIfNeeded()
        guard token == flow else { return }

        if restarting {
            assignedWords = AssessmentScoring.pickWords(
                from: isRetake ? AssessmentScoring.retakeWordTiers : AssessmentScoring.wordTiers
            )
        }
        currentIndex = 0
        yesCount = 0
        noCount = 0
        retryCount = 0
        readablePerSize = Array(repeating: 0, count: AssessmentScoring.textSizes.count)
        wordRead = Array(repeating: false, count: AssessmentScoring.textSizes.count)
        errorMessage = nil

        statusMessage = "Assessment is starting…"
        if !micAvailable {
            await say("Microphone access is off. You can still answer with the buttons. \(Self.buttonColorHint)", token)
            guard token == flow else { return }
        }
        try? await Task.sleep(for: .milliseconds(700))
        guard token == flow else { return }
        await say(isRetake
                  ? "Welcome. We will now begin the retake text size assessment."
                  : "Welcome. We will now begin the text size assessment.", token)
        guard token == flow else { return }
        await askCurrentQuestion()
    }

    // MARK: - Yes/no question

    private func askCurrentQuestion() async {
        guard currentWord != nil else { return }
        stopListening()
        flow += 1
        let token = flow
        retryCount = 0
        typedAnswer = ""
        phase = .asking
        statusMessage = "Can you read this word? Say yes or no, or tap a button."
        await speakQuestion("Can you read this word? Please say yes or no, or tap the button.", token)
    }

    private func repeatCurrentQuestion() async {
        stopListening()
        flow += 1
        let token = flow
        phase = .asking
        statusMessage = "Repeating question…"
        await speakQuestion("Repeating. Can you read this word? Please say yes or no.", token)
    }

    /// speakQuestion(): speak, then start listening 600ms after it ends.
    private func speakQuestion(_ text: String, _ token: Int) async {
        await say(text, token)
        guard token == flow else { return }
        phase = .awaitingAnswer
        try? await Task.sleep(for: .milliseconds(600))
        guard token == flow else { return }
        listenForYesNo(token)
    }

    private func listenForYesNo(_ token: Int) {
        guard micAvailable else {
            statusMessage = "Microphone permission not granted. Tap Yes or No."
            Task { await say("Microphone permission is not granted. \(Self.buttonColorHint)", token) }
            return
        }
        isListening = true
        statusMessage = "Listening… You may also tap Yes or No."
        cascade.listen(contextualStrings: ["yes", "no", "repeat", "stop"], respectsPreferences: false) { [weak self] event in
            guard let self, token == self.flow, self.phase == .awaitingAnswer else { return }
            switch event {
            case .partial(let text):
                self.statusMessage = "Hearing: \(text)"
                // Act on a clear answer right away (onPartialResults).
                if let action = Self.answerAction(for: text) {
                    self.perform(action)
                }
            case .final(let text):
                self.isListening = false
                self.statusMessage = "Heard: \(text)"
                if let action = Self.answerAction(for: text) {
                    self.perform(action)
                } else {
                    Task { await self.retryOrWaitForButton("Please answer yes or no.", token) }
                }
            case .error, .timedOut:
                self.isListening = false
                Task { await self.retryOrWaitForButton("I did not hear your answer.", token) }
            }
        }
    }

    private enum VoiceAction { case repeatQuestion, stop, yes, no }

    private static func answerAction(for text: String) -> VoiceAction? {
        if YesNoMatcher.isRepeat(text) { return .repeatQuestion }
        if YesNoMatcher.isStop(text) { return .stop }
        if YesNoMatcher.isYes(text) { return .yes }
        if YesNoMatcher.isNo(text) { return .no }
        return nil
    }

    private func perform(_ action: VoiceAction) {
        stopListening()
        switch action {
        case .repeatQuestion: Task { await repeatCurrentQuestion() }
        case .stop: Task { await stopByVoice() }
        case .yes: Task { await beginReadAloudPhase() }
        case .no: recordAnswer(read: false)
        }
    }

    private func retryOrWaitForButton(_ message: String, _ token: Int) async {
        guard token == flow, phase == .awaitingAnswer else { return }
        retryCount += 1
        if retryCount >= Self.maxRetry {
            statusMessage = "\(message) Please tap Yes or No to continue."
            await say("No clear voice detected. Please tap yes or no to continue. \(Self.buttonColorHint)", token)
            return
        }
        statusMessage = "\(message) Say yes or no, or tap Yes/No."
        await speakQuestion("\(message) Please say yes or no.", token)
    }

    // MARK: - Buttons

    /// A tapped "Yes" is verified by typing the word; a spoken one by
    /// reading it aloud (handleManualAnswer).
    func tapYesButton() {
        guard canAnswerWithButtons else { return }
        stopListening()
        Task { await beginTypedWordPhase() }
    }

    func tapNoButton() {
        guard canAnswerWithButtons else { return }
        stopListening()
        recordAnswer(read: false)
    }

    // MARK: - Verifying a "yes"

    private func beginTypedWordPhase() async {
        flow += 1
        let token = flow
        phase = .verifyingTyped
        typedAnswer = ""
        statusMessage = "Type the word you read, then press Submit."
        await say("Please type the word you read, then press Submit.", token)
    }

    func submitTypedWord() {
        guard phase == .verifyingTyped, let word = currentWord else { return }
        let typed = typedAnswer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !typed.isEmpty else {
            statusMessage = "Please type the word first, then press Submit."
            return
        }
        recordAnswer(read: TextSimilarity.matches(candidate: typed, target: word))
    }

    private func beginReadAloudPhase() async {
        guard currentWord != nil else { return }
        flow += 1
        let token = flow
        phase = .verifyingReadAloud
        statusMessage = "Please read the word out loud now."
        await say("Great. Please read the word out loud now.", token)
        guard token == flow else { return }

        guard micAvailable else {
            // Can't hear them read it — same as a failed read-aloud.
            recordAnswer(read: false)
            return
        }
        let deadline = ContinuousClock.now + Self.readAloudBudget
        try? await Task.sleep(for: .milliseconds(400))
        guard token == flow else { return }
        readAloudAttempt(token: token, deadline: deadline)
    }

    /// One listening attempt of up to 6s, retried until a match or until
    /// the 15s budget runs out (startAndroidReadAloudListening +
    /// scheduleReadAloudRetryOrFinish).
    private func readAloudAttempt(token: Int, deadline: ContinuousClock.Instant) {
        guard token == flow, phase == .verifyingReadAloud, let word = currentWord else { return }
        let now = ContinuousClock.now
        guard now < deadline else {
            recordAnswer(read: false)
            return
        }

        flow += 1
        let attempt = flow
        isListening = true
        statusMessage = "Listening for your reading…"

        let timeout = min(deadline - now, Self.readAloudAttemptTimeout)
        Task {
            try? await Task.sleep(for: timeout)
            guard attempt == self.flow, self.phase == .verifyingReadAloud else { return }
            self.retryReadAloud(attempt: attempt, deadline: deadline)
        }

        cascade.listen(contextualStrings: [word], respectsPreferences: false) { [weak self] event in
            guard let self, attempt == self.flow, self.phase == .verifyingReadAloud else { return }
            switch event {
            case .partial(let text):
                if TextSimilarity.matches(candidate: text, target: word) {
                    self.stopListening()
                    self.recordAnswer(read: true)
                } else {
                    self.statusMessage = "Hearing: \(text)"
                }
            case .final(let text):
                if TextSimilarity.matches(candidate: text, target: word) {
                    self.stopListening()
                    self.recordAnswer(read: true)
                } else {
                    self.statusMessage = "Heard: \(text)"
                    self.retryReadAloud(attempt: attempt, deadline: deadline)
                }
            case .error, .timedOut:
                self.retryReadAloud(attempt: attempt, deadline: deadline)
            }
        }
    }

    private func retryReadAloud(attempt: Int, deadline: ContinuousClock.Instant) {
        stopListening()
        guard ContinuousClock.now < deadline else {
            recordAnswer(read: false)
            return
        }
        Task {
            try? await Task.sleep(for: .milliseconds(300))
            guard attempt == self.flow else { return }
            self.readAloudAttempt(token: attempt, deadline: deadline)
        }
    }

    // MARK: - Recording and moving on

    /// A "yes" only counts once the word was actually read (aloud or
    /// typed); a failed check counts as "no", exactly like Android's
    /// recordAnswer(false) after finishReadAloud(false)/a wrong typed word.
    private func recordAnswer(read: Bool) {
        guard isRunning, phase != .answered else { return }
        stopListening()
        flow += 1
        let token = flow
        phase = .answered
        typedAnswer = ""

        if read {
            yesCount += 1
            readablePerSize[currentIndex] += 1
            wordRead[currentIndex] = true
            statusMessage = "Saved: Yes"
        } else {
            noCount += 1
            statusMessage = "Saved: No"
        }

        Task {
            try? await Task.sleep(for: .milliseconds(550))
            guard token == flow else { return }
            await moveToNextItem(token)
        }
    }

    private func moveToNextItem(_ token: Int) async {
        guard currentIndex + 1 < assignedWords.count else {
            await finishTest(token)
            return
        }
        currentIndex += 1
        try? await Task.sleep(for: .milliseconds(900))
        guard token == flow else { return }
        await askCurrentQuestion()
    }

    private func finishTest(_ token: Int) async {
        phase = .completing
        statusMessage = "Assessment complete."
        await say("Assessment complete. Let's look at your results.", token)
        guard token == flow else { return }

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

        // Saved locally first (saveTestResult), then to the server; the
        // result screen opens either way, like goToNextScreen().
        FontSizePreferences.saveRecommendedSize(Double(recommendedSize))
        if let token = sessionStore.sessionToken {
            statusMessage = "Saving your results…"
            do {
                try await assessmentRepository.saveAssessment(
                    sessionToken: token,
                    impairmentLevel: impairment.rawValue,
                    recommendedTextSize: Int(recommendedSize),
                    yesCount: yesCount,
                    noCount: noCount
                )
                sessionStore.applyAssessment(
                    impairmentLevel: impairment.rawValue,
                    recommendedTextSize: Double(Int(recommendedSize))
                )
            } catch SupabaseError.sessionExpired {
                sessionStore.forceLogout()
                return
            } catch {
                errorMessage = "Your result couldn't be saved online: \(error.localizedDescription)"
            }
        }
        phase = .finished
    }

    // MARK: - Stopping

    /// Voice "stop": ends this run but stays on screen so Start can begin
    /// again with fresh words (stopByVoice).
    private func stopByVoice() async {
        stopListening()
        flow += 1
        let token = flow
        phase = .stopped
        typedAnswer = ""
        statusMessage = "Assessment stopped."
        await say("Assessment stopped. You may press start again if you want to retake.", token)
    }

    /// Leaves the screen entirely (retake only — the first assessment is
    /// required).
    func cancel() {
        stopEverything()
        wasCancelled = true
        phase = .finished
    }

    /// onPause/onDestroy.
    func stopEverything() {
        flow += 1
        stopListening()
        tts.stop()
    }

    private func stopListening() {
        cascade.stop()
        isListening = false
    }

    /// Speaks unless this step was superseded before it started.
    private func say(_ text: String, _ token: Int) async {
        guard token == flow else { return }
        tts.stop()
        await tts.speak(text, respectsPreferences: false)
    }
}
