import Foundation
import Observation
import CoreGraphics

enum FeedbackStep: Int, CaseIterable {
    case rating, textSize, speed, materialFeedback, instructorFeedback
}

/// Port of FeedbackActivity.java: rating, text size, reading speed,
/// material feedback and instructor feedback, shown one step at a time.
///
/// Like Android it's voice-first: on open it offers a guided voice flow
/// that asks each question, confirms the answer ("is that correct?"),
/// lets the student try a text size / speed before keeping it, reads a
/// summary back and submits. Everything can also be tapped or typed, and
/// "Use Voice" dictates into the two comment boxes.
@MainActor
@Observable
final class FeedbackViewModel {
    let material: LearningMaterial

    private(set) var step: FeedbackStep = .rating
    var rating: Int = 0

    private(set) var textSizeLevel = 0
    private(set) var workTextSize: CGFloat
    private let baseTextSize: CGFloat
    private var textSizeAdjustments = 0

    private(set) var speedLevel = 0
    private(set) var workSpeedScale: Double
    private let baseSpeedScale: Double
    private var speedAdjustments = 0

    var materialFeedbackText = ""
    var instructorFeedbackText = ""

    private(set) var isSubmitting = false
    private(set) var didSubmit = false
    var errorMessage: String?

    // Voice
    private(set) var voiceStatus = "Voice Status: Idle"
    private(set) var isListening = false
    private(set) var isVoiceMode = false
    /// "Going back to materials" was said; the view closes itself.
    var requestedClose = false
    private var isConfirming = false
    private var pendingValue = ""
    private var retryCount = 0
    private var flow = 0
    private var screenActive = false
    private var didGreet = false
    private static let maxRetry = 4

    private let feedbackRepository: FeedbackRepository
    private let voiceSettingsRepository: VoiceSettingsRepository
    private let tts: CloudTTSService
    private let cascade = SpeechCascadeSession()
    private let micPermission = MicPermissionService.shared
    private let sessionStore: SessionStore

    init(
        material: LearningMaterial,
        feedbackRepository: FeedbackRepository = FeedbackRepository(),
        voiceSettingsRepository: VoiceSettingsRepository = VoiceSettingsRepository(),
        tts: CloudTTSService = .shared,
        sessionStore: SessionStore = .shared
    ) {
        self.material = material
        self.feedbackRepository = feedbackRepository
        self.voiceSettingsRepository = voiceSettingsRepository
        self.tts = tts
        self.sessionStore = sessionStore

        // The size the student is actually using (FontSizeManager).
        let initialSize = CGFloat(FontSizePreferences.fontSize)
        baseTextSize = initialSize
        workTextSize = initialSize
        baseSpeedScale = 1.0
        workSpeedScale = 1.0
    }

    var textSizePreviewText: String {
        guard textSizeLevel != 0 else { return "" }
        if textSizeLevel == 3 {
            return "Kept at \(FeedbackPreferences.formatted(workTextSize))sp"
        }
        return "Showing \(FeedbackPreferences.formatted(workTextSize))sp (was \(FeedbackPreferences.formatted(baseTextSize))sp). Adjust again, or choose Just right to keep it."
    }

    var speedPreviewText: String {
        guard speedLevel != 0 else { return "" }
        if speedLevel == 3 {
            return "Kept at \(FeedbackPreferences.percent(workSpeedScale))%"
        }
        return "Now reading at \(FeedbackPreferences.percent(workSpeedScale))% (was \(FeedbackPreferences.percent(baseSpeedScale))%). Adjust again, or choose Just right to keep it."
    }

    func selectTextSizeLevel(_ level: Int) {
        textSizeLevel = level
        if level != 3 {
            workTextSize = FeedbackPreferences.targetTextSize(current: workTextSize, level: level)
            textSizeAdjustments += 1
        }
    }

    /// Tap on a speed option: one step, heard straight away.
    func selectSpeedLevel(_ level: Int) {
        applySpeedLevel(level)
        Task { await playSpeedSample() }
    }

    private func applySpeedLevel(_ level: Int) {
        speedLevel = level
        if level != 3 {
            workSpeedScale = FeedbackPreferences.targetSpeedScale(current: workSpeedScale, level: level)
            speedAdjustments += 1
        }
    }

    private func playSpeedSample() async {
        await tts.speak(
            "This is how fast I will read to you. Tell me if it feels right.",
            rateScale: workSpeedScale,
            respectsPreferences: false
        )
    }

    var canProceed: Bool {
        switch step {
        case .rating: return rating > 0
        default: return true
        }
    }

    var isLastStep: Bool { step == .instructorFeedback }

    /// Next/Back buttons: tapping through by hand ends the guided voice
    /// flow so the two don't fight over the current step.
    func goToNextStep() {
        stopVoiceFlow()
        guard canProceed else { return }
        guard let next = FeedbackStep(rawValue: step.rawValue + 1) else {
            Task { await submit() }
            return
        }
        step = next
    }

    func goToPreviousStep() {
        stopVoiceFlow()
        guard let previous = FeedbackStep(rawValue: step.rawValue - 1) else { return }
        step = previous
    }

    // MARK: - Screen lifecycle

    /// onCreate's greeting, then straight into the voice flow.
    func screenAppeared() {
        screenActive = true
        guard !didGreet else { return }
        didGreet = true
        let token = nextFlow()
        Task {
            await say("Feedback. I will guide you through your rating and feedback by voice. "
                      + "You can also tap a field to type instead.", token)
            guard token == flow else { return }
            if await micPermission.requestIfNeeded() {
                guard token == flow else { return }
                startVoiceFeedbackFlow()
            } else {
                voiceStatus = "Microphone permission needed. You can still tap and type."
            }
        }
    }

    func screenDisappeared() {
        screenActive = false
        stopVoiceFlow()
        tts.stop()
    }

    // MARK: - Guided voice flow (startVoiceFeedbackFlow … finishVoiceFeedbackFlow)

    /// The "Voice Feedback" button, and the automatic start.
    func startVoiceFeedbackFlow() {
        isVoiceMode = true
        isConfirming = false
        retryCount = 0
        pendingValue = ""
        step = .rating
        voiceStatus = "Voice feedback started."
        let token = nextFlow()
        Task {
            await say("I will ask for your rating, the text size, the reading speed, "
                      + "then your feedback, one at a time.", token)
            guard token == flow else { return }
            try? await Task.sleep(for: .milliseconds(400))
            await promptCurrentStep(token)
        }
    }

    func stopVoiceFlow() {
        isVoiceMode = false
        isConfirming = false
        flow += 1
        stopListening()
    }

    private func promptCurrentStep(_ token: Int) async {
        guard isVoiceMode, token == flow else { return }
        isConfirming = false
        voiceStatus = "Say your \(stepName):\n\(answerHint)"
        await say(promptForStep, token)
        listenForStep(token)
    }

    private func listenForStep(_ token: Int) {
        guard isVoiceMode, token == flow, screenActive else { return }
        isListening = true
        cascade.listen(contextualStrings: contextualStrings, respectsPreferences: false) { [weak self] event in
            guard let self, token == self.flow else { return }
            switch event {
            case .partial(let text):
                self.voiceStatus = "Hearing: \(text)"
            case .final(let text):
                self.isListening = false
                self.retryCount = 0
                Task { await self.handleSpokenText(text, token) }
            case .timedOut, .error:
                self.isListening = false
                Task { await self.retryOrFallbackToManual(token) }
            }
        }
    }

    private func handleSpokenText(_ spoken: String, _ token: Int) async {
        guard isVoiceMode, token == flow else { return }
        let lower = spoken.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)

        if lower == "cancel" || lower == "stop" {
            stopVoiceFlow()
            voiceStatus = "Voice feedback cancelled."
            await say("Voice feedback cancelled.", flow)
            return
        }
        if ["materials", "back", "go back", "back to materials", "bumalik"].contains(lower) {
            stopVoiceFlow()
            voiceStatus = "Returning to materials."
            await say("Going back to materials.", flow)
            requestedClose = true
            return
        }

        if isConfirming {
            await handleConfirmation(lower, token)
            return
        }

        if step != .rating && Self.isSkip(lower) {
            let skipped = stepName
            await say("\(skipped) skipped.", token)
            guard token == flow else { return }
            await advance(token)
            return
        }

        switch step {
        case .rating:
            let stars = Self.parseRating(lower)
            guard stars > 0 else {
                await say("I didn't catch a number from 1 to 5. Please say a rating from 1 to 5 stars.", token)
                listenForStep(token)
                return
            }
            voiceStatus = "Heard: \(stars) stars"
            await confirm(String(stars), token)
        case .textSize, .speed:
            let isSize = step == .textSize
            let level = Self.parseScaleAnswer(lower, isSize: isSize)
            guard level > 0 else {
                await say("I didn't catch that. \(promptForStep)", token)
                listenForStep(token)
                return
            }
            voiceStatus = "Heard: \((isSize ? FeedbackPreferences.sizeLabels : FeedbackPreferences.speedLabels)[level - 1])"
            await confirm(String(level), token)
        case .materialFeedback, .instructorFeedback:
            let text = spoken.trimmingCharacters(in: .whitespacesAndNewlines)
            voiceStatus = "Heard: \(text)"
            await confirm(text, token)
        }
    }

    private func confirm(_ value: String, _ token: Int) async {
        isConfirming = true
        pendingValue = value
        let shown: String
        switch step {
        case .textSize: shown = FeedbackPreferences.sizeLabels[(Int(value) ?? 3) - 1]
        case .speed: shown = FeedbackPreferences.speedLabels[(Int(value) ?? 3) - 1]
        default: shown = value
        }
        voiceStatus = "Confirm: \(shown)\nSay yes or no"
        let message: String
        switch step {
        case .rating: message = "I heard \(value) stars. Is that correct? Say yes or no."
        case .textSize, .speed: message = "I heard \(shown.lowercased()). Is that correct? Say yes or no."
        default: message = "I heard: \(value). Is that correct? Say yes or no."
        }
        await say(message, token)
        listenForStep(token)
    }

    private func handleConfirmation(_ lower: String, _ token: Int) async {
        if Self.isYes(lower) {
            isConfirming = false
            retryCount = 0
            commit(pendingValue)
            // Size/speed: anything but "just right" is previewed, then the
            // same question is asked again from the new setting.
            if (step == .textSize || step == .speed) && pendingValue != "3" {
                if step == .speed {
                    await playSpeedSample()
                } else {
                    await say("Now the text is \(FeedbackPreferences.formatted(workTextSize)). Look at the sample on the screen.", token)
                }
                guard token == flow else { return }
                try? await Task.sleep(for: .milliseconds(400))
                await promptCurrentStep(token)
                return
            }
            try? await Task.sleep(for: .milliseconds(600))
            await advance(token)
        } else if Self.isNo(lower) {
            isConfirming = false
            retryCount = 0
            pendingValue = ""
            await say("Okay, please say it again.", token)
            try? await Task.sleep(for: .milliseconds(400))
            await promptCurrentStep(token)
        } else {
            retryCount += 1
            if retryCount <= Self.maxRetry {
                await say("Please say yes to confirm or no to try again.", token)
                listenForStep(token)
            } else {
                retryCount = 0
                isConfirming = false
                await say("Moving on. Please say it again.", token)
                try? await Task.sleep(for: .milliseconds(400))
                await promptCurrentStep(token)
            }
        }
    }

    private func commit(_ value: String) {
        switch step {
        case .rating:
            rating = Int(value) ?? rating
            voiceStatus = "Rating saved: \(rating) \(rating == 1 ? "star" : "stars")"
        case .textSize:
            selectTextSizeLevel(Int(value) ?? 3)
            voiceStatus = "Text size: showing \(FeedbackPreferences.formatted(workTextSize))sp"
        case .speed:
            applySpeedLevel(Int(value) ?? 3)
            voiceStatus = "Reading speed: now \(FeedbackPreferences.percent(workSpeedScale))%"
        case .materialFeedback:
            materialFeedbackText = value
            voiceStatus = "Material feedback saved."
        case .instructorFeedback:
            instructorFeedbackText = value
            voiceStatus = "Instructor feedback saved."
        }
    }

    private func advance(_ token: Int) async {
        guard let next = FeedbackStep(rawValue: step.rawValue + 1) else {
            await finishVoiceFeedbackFlow(token)
            return
        }
        step = next
        await promptCurrentStep(token)
    }

    private func retryOrFallbackToManual(_ token: Int) async {
        guard isVoiceMode, token == flow else { return }
        retryCount += 1
        if retryCount <= Self.maxRetry {
            voiceStatus = "Didn't catch that. Retrying..."
            await say("I did not hear you clearly. Please speak closer to the microphone, "
                      + "speak a little louder, or speak more slowly and clearly, and try again.", token)
            listenForStep(token)
        } else {
            retryCount = 0
            isVoiceMode = false
            voiceStatus = "Please type manually or press Voice Feedback again."
            await say("I am having trouble hearing you. "
                      + "You may fill this in manually, then press Voice Feedback to continue.", token)
        }
    }

    private func finishVoiceFeedbackFlow(_ token: Int) async {
        isVoiceMode = false
        isConfirming = false
        voiceStatus = "Reviewing your feedback..."
        await say(feedbackSummary, token)
        guard token == flow else { return }
        stopListening()
        await submit()
    }

    private var feedbackSummary: String {
        var text = "Here is a summary of your feedback. "
        text += "Rating: \(rating) \(rating == 1 ? "star" : "stars"). "
        text += "Text size: \(textSizeLevel == 0 ? "not answered" : FeedbackPreferences.sizeLabels[textSizeLevel - 1].lowercased()). "
        text += "Reading speed: \(speedLevel == 0 ? "not answered" : FeedbackPreferences.speedLabels[speedLevel - 1].lowercased()). "
        let materialText = materialFeedbackText.trimmingCharacters(in: .whitespacesAndNewlines)
        text += "Material feedback: \(materialText.isEmpty ? "none provided" : materialText). "
        let instructorText = instructorFeedbackText.trimmingCharacters(in: .whitespacesAndNewlines)
        text += "Instructor feedback: \(instructorText.isEmpty ? "none provided" : instructorText). "
        text += "Submitting your feedback now."
        return text
    }

    // MARK: - "Use Voice" dictation (startFieldDictation)

    /// Appends what's said to the current comment box, no confirmation.
    func dictateIntoCurrentField() {
        guard step == .materialFeedback || step == .instructorFeedback else { return }
        stopVoiceFlow()
        let token = flow
        Task {
            guard await micPermission.requestIfNeeded(), token == flow else {
                voiceStatus = "Microphone permission needed."
                return
            }
            isListening = true
            voiceStatus = "Listening... speak your feedback."
            cascade.listen(respectsPreferences: false) { [weak self] event in
                guard let self, token == self.flow else { return }
                switch event {
                case .partial(let text):
                    self.voiceStatus = "Hearing: \(text)"
                case .final(let text):
                    self.isListening = false
                    self.append(text)
                    self.voiceStatus = "Added to your feedback."
                case .timedOut, .error:
                    self.isListening = false
                    self.voiceStatus = "Didn't catch that. Tap Use Voice to try again."
                }
            }
        }
    }

    private func append(_ spoken: String) {
        let text = spoken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        if step == .materialFeedback {
            materialFeedbackText = materialFeedbackText.isEmpty ? text : materialFeedbackText + " " + text
        } else {
            instructorFeedbackText = instructorFeedbackText.isEmpty ? text : instructorFeedbackText + " " + text
        }
    }

    // MARK: - Step text

    private var stepName: String {
        switch step {
        case .rating: return "Rating"
        case .textSize: return "Text Size"
        case .speed: return "Reading Speed"
        case .materialFeedback: return "Material Feedback"
        case .instructorFeedback: return "Instructor Feedback"
        }
    }

    private var promptForStep: String {
        switch step {
        case .rating:
            return "Please say your satisfaction rating, from 1 to 5 stars."
        case .textSize:
            return textSizeAdjustments == 0
                ? "How is the size of the text? Say much too small, a little small, just right, a little big, or much too big. Or say skip."
                : "How is the size now? Say just right to keep it, or say a little small, a little big, much too small, or much too big to adjust again. Or say skip."
        case .speed:
            return speedAdjustments == 0
                ? "How is the reading speed of the voice? Say much too slow, a little slow, just right, a little fast, or much too fast. Or say skip."
                : "How is the speed now? Say just right to keep it, or say a little slow, a little fast, much too slow, or much too fast to adjust again. Or say skip."
        case .materialFeedback:
            return "Please say your feedback about the learning material, or say skip to leave it blank."
        case .instructorFeedback:
            return "Please say your feedback for the instructor, or say skip to leave it blank."
        }
    }

    private var answerHint: String {
        switch step {
        case .rating: return "1 • 2 • 3 • 4 • 5 stars"
        case .textSize: return FeedbackPreferences.sizeLabels.joined(separator: " • ").lowercased() + "\n(or say skip)"
        case .speed: return FeedbackPreferences.speedLabels.joined(separator: " • ").lowercased() + "\n(or say skip)"
        default: return "say your comment, or say skip"
        }
    }

    private var contextualStrings: [String] {
        if isConfirming { return ["yes", "no", "correct", "cancel"] }
        switch step {
        case .rating: return ["one", "two", "three", "four", "five", "stars"]
        case .textSize: return FeedbackPreferences.sizeLabels.map { $0.lowercased() } + ["skip"]
        case .speed: return FeedbackPreferences.speedLabels.map { $0.lowercased() } + ["skip"]
        default: return ["skip"]
        }
    }

    // MARK: - Answer parsing (parseRating / parseScaleAnswer / isYes / isNo / isSkipCommand)

    static func parseRating(_ spoken: String) -> Int {
        let text = convertNumberWords(spoken.lowercased())
        for c in text where ("1"..."5").contains(c) {
            return Int(String(c)) ?? 0
        }
        return 0
    }

    /// A level 1…5 (0 = not understood): option names, "a little"/"much"
    /// + small/big or slow/fast, requests like "make it bigger", a few
    /// Tagalog words, and the digits 1–5.
    static func parseScaleAnswer(_ spoken: String, isSize: Bool) -> Int {
        let s = convertNumberWords(spoken.lowercased()).trimmingCharacters(in: .whitespaces)
        func any(_ needles: String...) -> Bool { needles.contains { s.contains($0) } }

        if any("just right", "perfect", "okay", "fine", "good", "tama", "ayos", "ok na") || s == "ok" {
            return 3
        }
        let extreme = any("much", "very", "too ", "sobra", "napaka", "extremely", "super") || s.hasSuffix(" too")
        let mild = any("little", "bit", "slightly", "somewhat", "medyo", "konti", "kaunti")

        // "Make it bigger" means it is currently too small.
        let wantsUp = isSize ? any("bigger", "larger", "lakihan", "palakihin", "increase")
                             : any("faster", "bilisan", "speed up")
        let wantsDown = isSize ? any("smaller", "liitan", "paliitin", "decrease", "reduce")
                               : any("slower", "bagalan", "slow down")
        if wantsUp && !wantsDown { return (extreme && !mild) ? 1 : 2 }
        if wantsDown && !wantsUp { return (extreme && !mild) ? 5 : 4 }

        let low = isSize ? any("small", "tiny", "liit") : any("slow", "bagal")
        let high = isSize ? any("big", "large", "huge", "laki") : any("fast", "quick", "rapid", "bilis")
        if low && !high { return (extreme && !mild) ? 1 : 2 }
        if high && !low { return (extreme && !mild) ? 5 : 4 }

        for c in s where ("1"..."5").contains(c) {
            return Int(String(c)) ?? 0
        }
        return 0
    }

    private static func convertNumberWords(_ input: String) -> String {
        input.replacingOccurrences(of: "one", with: "1")
            .replacingOccurrences(of: "two", with: "2")
            .replacingOccurrences(of: "three", with: "3")
            .replacingOccurrences(of: "four", with: "4")
            .replacingOccurrences(of: "five", with: "5")
    }

    static func isYes(_ text: String) -> Bool {
        ["yes", "yeah", "yep", "yup", "correct", "right"].contains(text)
            || text.contains("yes") || text.contains("correct")
    }

    static func isNo(_ text: String) -> Bool {
        ["no", "nope", "nah", "wrong", "incorrect"].contains(text)
            || text.contains("no that") || text.hasPrefix("no ")
    }

    static func isSkip(_ text: String) -> Bool {
        text.contains("skip") || ["escape", "esc", "ship", "skit"].contains(text)
    }

    // MARK: - Speech helpers

    private func nextFlow() -> Int {
        flow += 1
        return flow
    }

    private func say(_ text: String, _ token: Int) async {
        guard token == flow, screenActive else { return }
        stopListening()
        await tts.speak(text, rateScale: workSpeedScale, respectsPreferences: false)
    }

    private func stopListening() {
        cascade.stop()
        isListening = false
    }

    // MARK: - Submit

    private func submit() async {
        stopVoiceFlow()
        guard let token = sessionStore.sessionToken else { return }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        let textSizeAnswer = FeedbackPreferences.describeTextSize(
            level: textSizeLevel, base: baseTextSize, work: workTextSize, adjustments: textSizeAdjustments
        )
        let speedAnswer = FeedbackPreferences.describeSpeed(
            level: speedLevel, base: baseSpeedScale, work: workSpeedScale, adjustments: speedAdjustments
        )
        let blob = FeedbackPreferences.feedbackBlob(
            rating: rating,
            textSizeAnswer: textSizeAnswer,
            speedAnswer: speedAnswer,
            materialFeedback: materialFeedbackText,
            instructorFeedback: instructorFeedbackText
        )

        do {
            let accepted = try await feedbackRepository.submit(
                sessionToken: token, materialId: material.id, rating: rating, feedbackText: blob
            )
            guard accepted else {
                errorMessage = "Feedback wasn't accepted — you may not have access to this material."
                return
            }
            // Keep what the student previewed (applyAdjustments).
            if workTextSize != baseTextSize {
                FontSizePreferences.saveRecommendedSize(Double(workTextSize))
            }
            if speedLevel != 0 && workSpeedScale != baseSpeedScale {
                try? await voiceSettingsRepository.save(sessionToken: token, ttsVoice: nil, speechRateScale: workSpeedScale)
            }
            didSubmit = true
            await tts.speak("Thank you. Your feedback was submitted.", respectsPreferences: false)
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
