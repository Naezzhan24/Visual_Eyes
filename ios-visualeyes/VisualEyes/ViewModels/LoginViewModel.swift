import Foundation
import Observation

/// Port of LoginActivity.java. Typed login, plus the voice flow: on open
/// it greets the student and asks "new or existing user?"; "new" opens
/// voice registration, "existing" (or the Voice Login button) asks for the
/// School ID, reads it back digit by digit for a yes/no, then asks for the
/// birthdate password and logs in. A remembered School ID skips straight
/// to the password.
@MainActor
@Observable
final class LoginViewModel {
    var schoolId: String = ""
    var password: String = "" {
        didSet {
            // Birthdate typing aid: 02272005 → 02-27-2005.
            let formatted = SpokenInputParser.formatBirthdateTyping(password)
            if formatted != password, password.allSatisfy({ $0.isNumber || $0 == "-" }) {
                password = formatted
            }
        }
    }
    var isLoading = false
    var errorMessage: String?

    private(set) var voiceStatus = "Ready."
    private(set) var isListening = false
    private(set) var isVoiceLoginMode = false
    /// "New user" was said; the view opens registration in voice mode.
    var requestedVoiceRegistration = false

    private enum VoiceStep { case schoolId, confirmSchoolId, password }
    private var step: VoiceStep = .schoolId
    private var isAwaitingEntryChoice = false
    private var schoolIdSpoken = ""
    private var voiceRetryCount = 0
    private var entryChoiceRetryCount = 0
    private var lastSpokenInstruction = ""
    private var flow = 0
    private var didGreet = false
    private let expiredMessage: String?

    private static let maxVoiceRetry = 4
    private static let maxEntryChoiceRetry = 2
    private static let speakingRate = 1.10

    private let sessionStore: SessionStore
    private let cascade: SpeechCascadeSession
    private let micPermission: MicPermissionService
    private let tts: CloudTTSService

    init(
        sessionStore: SessionStore = .shared,
        cascade: SpeechCascadeSession? = nil,
        micPermission: MicPermissionService = .shared,
        tts: CloudTTSService = .shared
    ) {
        self.sessionStore = sessionStore
        self.cascade = cascade ?? SpeechCascadeSession()
        self.micPermission = micPermission
        self.tts = tts
        self.schoolId = sessionStore.lastSchoolId
        self.expiredMessage = sessionStore.sessionExpiredMessage
    }

    var canSubmit: Bool {
        !schoolId.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty && !isLoading
    }

    /// Login button / end of the voice flow.
    func login() async {
        guard !isLoading else { return }
        guard canSubmit else {
            errorMessage = "Please enter your School ID and password."
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await sessionStore.login(
                schoolId: schoolId.trimmingCharacters(in: .whitespaces),
                password: password
            )
        } catch {
            errorMessage = error.localizedDescription
            let spoken: String
            switch error {
            case LoginError.invalidCredentials: spoken = "Incorrect School ID or password. Please try again."
            case LoginError.notApproved: spoken = "Your account has not been approved yet. Please wait for admin approval."
            default: spoken = "Login failed. Please check your connection or account details."
            }
            voiceStatus = spoken
            say(spoken)
        }
    }

    /// Login button tapped: typed login wins over any voice flow.
    func loginTapped() async {
        endVoiceInteraction()
        await login()
    }

    // MARK: - Opening the screen

    func screenAppeared() {
        // Back from Register: the new School ID is filled in for them
        // (LoginActivity's "registered_school_id" extra).
        let remembered = sessionStore.lastSchoolId
        if didGreet, !isVoiceLoginMode, !remembered.isEmpty, schoolId != remembered {
            schoolId = remembered
            password = ""
        }
        guard !didGreet else { return }
        didGreet = true
        let tips = expiredMessage ?? (!remembered.isEmpty
            ? "Welcome back to Visual E D. Your School ID has been filled in for you — just enter or say your password to continue."
            : "Welcome to Visual E D. Quick tip: triple tap anywhere on the screen to repeat the last instruction, or pinch with two fingers to zoom in.")

        micPermission.refresh()
        if micPermission.isAuthorized {
            say(tips) { [weak self] in self?.promptEntryChoice() }
        } else {
            say(tips + " I'll need microphone access for voice login — a permission dialog will appear next, please allow it.") { [weak self] in
                Task { await self?.requestMicThenStartVoiceLogin() }
            }
        }
    }

    /// Leaving Login (to Register, or into the app): silence the prompt
    /// and release the mic so neither carries into the next screen.
    func stopVoice() {
        endVoiceInteraction()
        tts.stop()
    }

    func repeatLastInstruction() {
        guard !lastSpokenInstruction.isEmpty else { return }
        say(lastSpokenInstruction, remember: false)
    }

    private func requestMicThenStartVoiceLogin() async {
        voiceStatus = "Requesting microphone access..."
        if await micPermission.requestIfNeeded() {
            voiceStatus = "Microphone enabled."
            startVoiceLogin()
        } else {
            voiceStatus = "Microphone permission denied."
            say("Microphone permission is required for voice login.")
        }
    }

    // MARK: - New or existing user

    private func promptEntryChoice() {
        isAwaitingEntryChoice = true
        voiceStatus = "New or existing user?"
        say("Are you a new user or an existing user? Say new to create an account, or say existing to log in.") { [weak self] in
            self?.listen()
        }
    }

    private func retryEntryChoice() {
        entryChoiceRetryCount += 1
        if entryChoiceRetryCount <= Self.maxEntryChoiceRetry {
            voiceStatus = "Please say new or existing."
            say("Sorry, I didn't catch that. Please say new, or existing.") { [weak self] in self?.listen() }
        } else {
            isAwaitingEntryChoice = false
            entryChoiceRetryCount = 0
            stopListening()
            voiceStatus = "Ready."
            say("No problem. You can tap Register to create an account, or log in manually or by voice whenever you're ready. "
                + "Triple tap the screen anytime to hear this again.")
        }
    }

    // MARK: - Voice login

    /// The Voice Login button.
    func voiceLoginTapped() {
        if isVoiceLoginMode {
            endVoiceInteraction()
            voiceStatus = "Voice login stopped."
            return
        }
        micPermission.refresh()
        if micPermission.isAuthorized {
            startVoiceLogin()
        } else {
            say("I need access to your microphone for voice login. A system permission dialog will appear next — please allow it.") { [weak self] in
                Task { await self?.requestMicThenStartVoiceLogin() }
            }
        }
    }

    private func startVoiceLogin() {
        isAwaitingEntryChoice = false
        isVoiceLoginMode = true
        voiceRetryCount = 0
        schoolIdSpoken = ""
        let remembered = sessionStore.lastSchoolId
        if remembered.isEmpty {
            step = .schoolId
            schoolId = ""
            password = ""
            voiceStatus = "Voice login started."
            say("Voice login started.") { [weak self] in self?.promptCurrentStep() }
        } else {
            step = .password
            schoolId = remembered
            password = ""
            voiceStatus = "Voice login started."
            say("Welcome back. Using \(SpokenInputParser.spellDigits(remembered)) as your School ID.") { [weak self] in
                self?.promptCurrentStep()
            }
        }
    }

    private func promptCurrentStep() {
        guard isVoiceLoginMode else { return }
        switch step {
        case .schoolId:
            voiceStatus = "Say your School ID..."
            say("Please say your School ID number.") { [weak self] in self?.listen() }
        case .confirmSchoolId:
            voiceStatus = "Confirm: \(schoolIdSpoken)"
            say("I heard your School ID as \(SpokenInputParser.spellDigits(schoolIdSpoken)). Is that correct? Say yes or no.") { [weak self] in
                self?.listen()
            }
        case .password:
            voiceStatus = "Say your birthdate as your password..."
            say("Your password is your birthdate. Please say it now, including the month, day, and year — for example, February 27, 2005. "
                + "If you're somewhere public, you may want to switch to manual login instead.") { [weak self] in
                self?.listen()
            }
        }
    }

    private func listen() {
        guard isVoiceLoginMode || isAwaitingEntryChoice else { return }
        let token = flow
        isListening = true
        voiceStatus = "Listening..."
        let contextual: [String]
        if isAwaitingEntryChoice {
            contextual = ["new", "existing", "register", "log in"]
        } else if step == .confirmSchoolId {
            contextual = ["yes", "no", "correct"]
        } else if step == .password {
            contextual = SpokenInputParser.monthNames
        } else {
            contextual = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
        }
        cascade.listen(contextualStrings: contextual) { [weak self] event in
            guard let self, token == self.flow else { return }
            switch event {
            case .partial(let text):
                self.voiceStatus = "Hearing: \(text)"
            case .final(let text):
                self.isListening = false
                self.voiceStatus = "Heard: \(text)"
                self.handleVoiceResult(text)
            case .timedOut, .error:
                self.isListening = false
                if self.isAwaitingEntryChoice {
                    self.retryEntryChoice()
                } else {
                    self.retryOrStop("I did not catch that.")
                }
            }
        }
    }

    private func handleVoiceResult(_ spoken: String) {
        let lower = spoken.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)

        if isAwaitingEntryChoice {
            if lower.contains("new") || lower.contains("register") {
                isAwaitingEntryChoice = false
                entryChoiceRetryCount = 0
                say("Opening registration.") { [weak self] in self?.requestedVoiceRegistration = true }
            } else if lower.contains("existing") || lower.contains("log") {
                isAwaitingEntryChoice = false
                entryChoiceRetryCount = 0
                startVoiceLogin()
            } else {
                retryEntryChoice()
            }
            return
        }

        guard isVoiceLoginMode else { return }
        guard !lower.isEmpty else {
            retryOrStop("I did not catch that.")
            return
        }
        if lower.contains("cancel") || lower.contains("stop") {
            abortVoiceLogin("Voice login cancelled.")
            return
        }
        if lower.contains("register") {
            isVoiceLoginMode = false
            say("Opening registration.") { [weak self] in self?.requestedVoiceRegistration = true }
            return
        }

        switch step {
        case .schoolId:
            schoolIdSpoken = SpokenInputParser.schoolId(from: lower)
            guard !schoolIdSpoken.isEmpty else {
                retryOrStop("I did not catch your School ID.")
                return
            }
            step = .confirmSchoolId
            promptCurrentStep()
        case .confirmSchoolId:
            if lower.contains("yes") || lower.contains("correct") || lower.contains("yep") || lower.contains("yeah") {
                voiceRetryCount = 0
                schoolId = schoolIdSpoken
                step = .password
                promptCurrentStep()
            } else {
                schoolIdSpoken = ""
                step = .schoolId
                say("Okay, please say your School ID again.") { [weak self] in self?.promptCurrentStep() }
            }
        case .password:
            let spokenPassword = SpokenInputParser.password(from: spoken)
            guard !spokenPassword.isEmpty else {
                retryOrStop("I did not catch your password.")
                return
            }
            password = spokenPassword
            isVoiceLoginMode = false
            voiceStatus = "Password received. Logging in..."
            say("Password received. Logging you in now.") { [weak self] in
                Task { await self?.login() }
            }
        }
    }

    private func retryOrStop(_ message: String) {
        voiceRetryCount += 1
        if voiceRetryCount <= Self.maxVoiceRetry {
            voiceStatus = message
            say(message + " Please speak closer to the microphone — it's at the bottom edge of your phone — "
                + "speak a little louder, or speak more slowly and clearly, and try again.") { [weak self] in
                self?.promptCurrentStep()
            }
        } else {
            abortVoiceLogin("I am having trouble hearing you. Please use manual login.")
        }
    }

    private func abortVoiceLogin(_ message: String) {
        voiceRetryCount = 0
        isVoiceLoginMode = false
        stopListening()
        voiceStatus = message
        say(message)
    }

    private func endVoiceInteraction() {
        isVoiceLoginMode = false
        isAwaitingEntryChoice = false
        flow += 1
        stopListening()
    }

    // MARK: - Speech helpers

    private func say(_ text: String, remember: Bool = true, then action: (() -> Void)? = nil) {
        if remember { lastSpokenInstruction = text }
        stopListening()
        flow += 1
        let token = flow
        Task {
            await tts.speak(text, rateScale: Self.speakingRate)
            guard token == flow else { return }
            action?()
        }
    }

    private func stopListening() {
        cascade.stop()
        isListening = false
    }
}
