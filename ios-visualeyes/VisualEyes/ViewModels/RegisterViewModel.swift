import Foundation
import Observation

/// Port of RegisterActivity.java: the typed form plus Voice Registration.
///
/// The voice flow asks each field in turn (School ID first), reads it back
/// for a yes/no — names letter by letter, with letter-by-letter correction
/// on "no" — and after the School ID checks the enrollment list and
/// autofills everything it can. It ends with a spoken summary and a final
/// "is all of this correct?" before registering. "Skip" leaves the middle
/// name or section blank; tapping a field switches to typing.
@MainActor
@Observable
final class RegisterViewModel {
    var firstName = ""
    var middleName = ""
    var lastName = ""
    var birthdate = Date() {
        didSet { birthdateEntered = true }
    }
    /// The DatePicker always holds a date; this says whether the student
    /// actually chose (or said) one, like Android's empty birthdate field.
    private(set) var birthdateEntered = false
    var yearLevel = ""
    var schoolId = ""
    var email = ""
    var section = ""

    var isCheckingSchoolId = false
    var isSubmitting = false
    var errorMessage: String?
    var successMessage: String?

    // Voice
    private(set) var voiceStatus = "Ready."
    private(set) var isListening = false
    private(set) var isVoiceMode = false
    /// Registration finished and was announced; the view returns to Login.
    var requestedReturnToLogin = false

    private enum Field: Int, CaseIterable {
        case schoolId, firstName, middleName, lastName, birthdate, yearLevel, email, section
    }
    private var field: Field = .schoolId
    private var pendingValue = ""
    private var retryCount = 0
    private var isConfirmingField = false
    private var isAwaitingFinalReview = false
    private var isAwaitingWrongIdConfirm = false
    private var isAwaitingIdRetryConfirm = false
    private var isAwaitingLetterPosition = false
    private var isAwaitingLetterValue = false
    private var correctingLetterPosition = 0
    private var detailsFromIdLookup = false
    private var lastSpokenInstruction = ""
    private var flow = 0
    private var didGreet = false
    private static let maxRetry = 4
    private static let speakingRate = 1.10

    private let authRepository: AuthRepository
    private let sessionStore: SessionStore
    private let tts: CloudTTSService
    private let cascade = SpeechCascadeSession()
    private let micPermission = MicPermissionService.shared
    private let nameNormalizer = NameNormalizer()

    init(
        authRepository: AuthRepository = AuthRepository(),
        sessionStore: SessionStore = .shared,
        tts: CloudTTSService = .shared
    ) {
        self.authRepository = authRepository
        self.sessionStore = sessionStore
        self.tts = tts
    }

    /// `p_birthdate` and `official_list.birth_date` are Postgres `date`s,
    /// which PostgREST reads/writes as ISO `yyyy-MM-dd` — the same format
    /// RegisterActivity uses. Uses the device time zone because the
    /// DatePicker yields local midnight; formatting that in UTC would shift
    /// the day back by one in the Philippines (UTC+8) and lock the student
    /// out, since their password is derived from this birthdate.
    private static let birthdateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        return formatter
    }()

    /// The login password format (`MM-DD-YYYY`) student_register derives.
    private static let passwordFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd-yyyy"
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        return formatter
    }()

    private static let spokenDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMMM d, yyyy"
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        return formatter
    }()

    var canSubmit: Bool {
        !firstName.trimmingCharacters(in: .whitespaces).isEmpty
            && !lastName.trimmingCharacters(in: .whitespaces).isEmpty
            && birthdateEntered
            && !yearLevel.trimmingCharacters(in: .whitespaces).isEmpty
            && !schoolId.trimmingCharacters(in: .whitespaces).isEmpty
            && !email.trimmingCharacters(in: .whitespaces).isEmpty
            && !isSubmitting
    }

    /// Autofills name/birthdate/year level/email/section from the official
    /// enrollment list — mirrors RegisterActivity's "Check School ID"
    /// button. Returns whether a record was found.
    @discardableResult
    func checkSchoolId() async -> Bool {
        let trimmed = schoolId.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return false }
        isCheckingSchoolId = true
        errorMessage = nil
        defer { isCheckingSchoolId = false }
        do {
            let match = try await authRepository.lookupEnrolledStudent(schoolId: trimmed)
            firstName = match.firstName
            middleName = match.middleName ?? ""
            lastName = match.lastName
            // Some drivers return a timestamp ("2005-02-27T00:00:00"); the
            // date part is all that matters.
            if let date = Self.birthdateFormatter.date(from: String(match.birthdate.prefix(10))) {
                birthdate = date
            }
            yearLevel = match.yearLevel ?? ""
            email = match.email ?? ""
            section = match.section ?? ""
            return true
        } catch SupabaseError.emptyResponse {
            errorMessage = "Could not find that School ID on the enrollment list."
        } catch {
            errorMessage = "Could not check the School ID. Please check your internet connection and try again."
        }
        return false
    }

    /// Check button: fills the form, then reads it back (reviewLoadedDetails).
    func checkSchoolIdTapped() async {
        endVoiceFlow()
        if await checkSchoolId() {
            say("Details loaded. " + reviewSummaryText + "Please review them, then press Continue.")
        } else if let errorMessage {
            say(errorMessage)
        }
    }

    /// Continue button (validateAndContinue).
    func continueTapped() async {
        endVoiceFlow()
        await validateAndSubmit()
    }

    private func validateAndSubmit() async {
        if firstName.trimmingCharacters(in: .whitespaces).isEmpty { return fail("First name is required.") }
        if lastName.trimmingCharacters(in: .whitespaces).isEmpty { return fail("Last name is required.") }
        if !birthdateEntered { return fail("Birthdate is required.") }
        if yearLevel.trimmingCharacters(in: .whitespaces).isEmpty { return fail("Year level is required.") }
        if schoolId.trimmingCharacters(in: .whitespaces).isEmpty { return fail("School ID is required.") }
        let trimmedEmail = email.trimmingCharacters(in: .whitespaces)
        if trimmedEmail.isEmpty || !trimmedEmail.contains("@") || !trimmedEmail.contains(".") {
            return fail("Please enter a valid email address.")
        }
        if await submit() {
            let approved = successMessage?.hasPrefix("Registration successful") == true
            sessionStore.lastSchoolId = schoolId.trimmingCharacters(in: .whitespaces)
            voiceStatus = "Registration submitted."
            say(approved
                ? "Registration successful. You can log in now using your birthdate as your password. Taking you to the login screen."
                : "Registration submitted successfully. Please wait for admin approval before logging in. Taking you to the login screen.") { [weak self] in
                self?.requestedReturnToLogin = true
            }
        } else if let errorMessage {
            say(errorMessage)
        }
    }

    private func fail(_ message: String) {
        errorMessage = message
        say(message)
    }

    /// Returns `true` on success.
    func submit() async -> Bool {
        guard canSubmit else { return false }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }
        do {
            let result = try await authRepository.register(
                firstName: firstName.trimmingCharacters(in: .whitespaces),
                middleName: middleName.isEmpty ? nil : middleName,
                lastName: lastName.trimmingCharacters(in: .whitespaces),
                birthdateISO: Self.birthdateFormatter.string(from: birthdate),
                yearLevel: yearLevel.trimmingCharacters(in: .whitespaces),
                schoolId: schoolId.trimmingCharacters(in: .whitespaces),
                email: email.trimmingCharacters(in: .whitespaces),
                section: section.isEmpty ? nil : section
            )
            let password = Self.passwordFormatter.string(from: birthdate)
            successMessage = result.approvalStatus.lowercased() == "approved"
                ? "Registration successful. You can log in now using your birthdate as your password (\(password))."
                : "Registered. Your account is pending instructor approval. Once approved, log in with your birthdate as your password (\(password))."
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    // MARK: - Screen lifecycle

    func screenAppeared(autoStartVoice: Bool) {
        guard !didGreet else { return }
        didGreet = true
        if autoStartVoice {
            say("Student registration. Let's continue by voice. You can switch to typing anytime by tapping a field.") { [weak self] in
                self?.voiceRegisterTapped()
            }
        } else {
            say("Student registration. You may fill in the fields manually, or press the Voice Registration button to fill each field by voice.")
        }
    }

    func screenDisappeared() {
        endVoiceFlow()
        tts.stop()
    }

    func repeatLastInstruction() {
        guard !lastSpokenInstruction.isEmpty else { return }
        say(lastSpokenInstruction, remember: false)
    }

    /// Tapping into a field to type ends the voice flow.
    func userStartedTyping() {
        if isVoiceMode {
            endVoiceFlow()
            voiceStatus = "Typing. Press Voice Registration to continue by voice."
        }
    }

    // MARK: - Voice registration

    func voiceRegisterTapped() {
        if isVoiceMode {
            endVoiceFlow()
            voiceStatus = "Voice registration stopped."
            return
        }
        Task {
            if await micPermission.requestIfNeeded() {
                startVoiceRegistration()
            } else {
                voiceStatus = "Microphone permission denied."
                say("Microphone permission is needed for voice registration. You can still type your details.")
            }
        }
    }

    private func startVoiceRegistration() {
        resetVoiceState()
        guard let first = firstEmptyField() else {
            voiceStatus = "All fields filled."
            say("All fields are already filled. Please review and press Continue.")
            return
        }
        isVoiceMode = true
        field = first
        voiceStatus = "Voice registration started."
        say("Voice registration started. I will ask you to say each field one by one.") { [weak self] in
            self?.promptCurrentField()
        }
    }

    private func resetVoiceState() {
        isConfirmingField = false
        isAwaitingFinalReview = false
        isAwaitingWrongIdConfirm = false
        isAwaitingIdRetryConfirm = false
        isAwaitingLetterPosition = false
        isAwaitingLetterValue = false
        retryCount = 0
        pendingValue = ""
    }

    private func endVoiceFlow() {
        isVoiceMode = false
        resetVoiceState()
        flow += 1
        stopListening()
    }

    private func isEmpty(_ f: Field) -> Bool {
        switch f {
        case .schoolId: return schoolId.trimmingCharacters(in: .whitespaces).isEmpty
        case .firstName: return firstName.trimmingCharacters(in: .whitespaces).isEmpty
        case .middleName: return middleName.trimmingCharacters(in: .whitespaces).isEmpty
        case .lastName: return lastName.trimmingCharacters(in: .whitespaces).isEmpty
        case .birthdate: return !birthdateEntered
        case .yearLevel: return yearLevel.trimmingCharacters(in: .whitespaces).isEmpty
        case .email: return email.trimmingCharacters(in: .whitespaces).isEmpty
        case .section: return section.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private func firstEmptyField(from start: Field = .schoolId) -> Field? {
        Field.allCases.first { $0.rawValue >= start.rawValue && isEmpty($0) }
    }

    private func promptCurrentField() {
        guard isVoiceMode else { return }
        guard let next = firstEmptyField(from: field) else {
            presentFinalReview()
            return
        }
        field = next
        isConfirmingField = false
        isAwaitingLetterPosition = false
        isAwaitingLetterValue = false
        voiceStatus = "Say your \(fieldName)..."
        say(promptForField) { [weak self] in self?.listen() }
    }

    private func moveToNextField() {
        guard let next = Field(rawValue: field.rawValue + 1) else {
            presentFinalReview()
            return
        }
        field = next
        promptCurrentField()
    }

    private func listen() {
        guard isVoiceMode else { return }
        let token = flow
        isListening = true
        voiceStatus = "Listening..."
        cascade.listen(contextualStrings: contextualStrings, respectsPreferences: false) { [weak self] event in
            guard let self, token == self.flow else { return }
            switch event {
            case .partial(let text):
                self.voiceStatus = "Hearing: \(text)"
            case .final(let text):
                self.isListening = false
                self.handleSpokenText(text)
            case .timedOut, .error:
                self.isListening = false
                self.retryOrFallbackToManual()
            }
        }
    }

    private var contextualStrings: [String] {
        if isConfirmingField || isAwaitingFinalReview || isAwaitingWrongIdConfirm || isAwaitingIdRetryConfirm {
            return ["yes", "no", "correct"]
        }
        if isAwaitingLetterPosition { return ["letter 1", "letter 2", "letter 3", "start over"] }
        if isAwaitingLetterValue { return ["double"] }
        switch field {
        case .schoolId: return ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
        case .birthdate: return SpokenInputParser.monthNames
        case .yearLevel: return ["first year", "second year", "third year", "fourth year"]
        case .email: return ["at", "dot", "gmail", "yahoo", "com", "edu", "ph"]
        case .middleName, .section: return ["skip"]
        default: return []
        }
    }

    private func retryOrFallbackToManual() {
        retryCount += 1
        if retryCount <= Self.maxRetry {
            voiceStatus = "Didn't catch that. Retrying..."
            say("I did not hear you clearly. Please speak closer to the microphone — it's at the bottom edge of your phone — "
                + "speak a little louder, or speak more slowly and clearly, and try again.") { [weak self] in
                self?.listen()
            }
        } else {
            let wasReview = isAwaitingFinalReview || isAwaitingWrongIdConfirm
            endVoiceFlow()
            if wasReview {
                voiceStatus = "Please review, then press Continue."
                say("I am having trouble hearing you. Please review your details above, then press Continue when you're ready.")
            } else {
                voiceStatus = "Please type manually or press Voice Registration again."
                say("I am having trouble hearing you. You may type this field manually, then press Voice Registration to continue.")
            }
        }
    }

    private func handleSpokenText(_ spoken: String) {
        guard isVoiceMode else { return }
        let lower = spoken.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)

        if lower.contains("cancel") || lower == "stop" {
            endVoiceFlow()
            voiceStatus = "Voice registration cancelled."
            say("Voice registration cancelled.")
            return
        }

        if isAwaitingFinalReview {
            if FeedbackViewModel.isYes(lower) {
                isAwaitingFinalReview = false
                isVoiceMode = false
                retryCount = 0
                say("Great. Submitting your registration now.") { [weak self] in
                    Task { await self?.validateAndSubmit() }
                }
            } else if FeedbackViewModel.isNo(lower) {
                isAwaitingFinalReview = false
                isAwaitingWrongIdConfirm = true
                retryCount = 0
                voiceStatus = "Is the School ID wrong?"
                say("Is your School ID the one that is wrong? Say yes to say your School ID again, or no to fix the other details yourself.") { [weak self] in
                    self?.listen()
                }
            } else {
                retryCount += 1
                if retryCount <= Self.maxRetry {
                    say("Please say yes to continue or no to make changes.") { [weak self] in self?.listen() }
                } else {
                    endVoiceFlow()
                    voiceStatus = "Please review manually, then press Continue."
                    say("Let's continue manually. Please review the fields, then press Continue.")
                }
            }
            return
        }

        if isAwaitingWrongIdConfirm {
            if FeedbackViewModel.isYes(lower) {
                isAwaitingWrongIdConfirm = false
                retryCount = 0
                reaskSchoolId("Okay, let's do your School ID again.")
            } else if FeedbackViewModel.isNo(lower) {
                endVoiceFlow()
                voiceStatus = "Please edit manually, then press Continue."
                say("Okay, please review and edit the fields manually, then press Continue when you're ready.")
            } else {
                retryCount += 1
                if retryCount <= Self.maxRetry {
                    say("Please say yes to say your School ID again, or no to fix the details yourself.") { [weak self] in self?.listen() }
                } else {
                    endVoiceFlow()
                    voiceStatus = "Please review manually, then press Continue."
                    say("Let's continue manually. Please review the fields, then press Continue.")
                }
            }
            return
        }

        if isAwaitingIdRetryConfirm {
            if FeedbackViewModel.isYes(lower) {
                isAwaitingIdRetryConfirm = false
                retryCount = 0
                reaskSchoolId("Okay, please say your School ID again.")
            } else if FeedbackViewModel.isNo(lower) || retryCount >= Self.maxRetry {
                isAwaitingIdRetryConfirm = false
                retryCount = 0
                voiceStatus = "Continuing by voice..."
                say("Okay, let's continue by voice for the rest of your details.") { [weak self] in
                    self?.promptCurrentField()
                }
            } else {
                retryCount += 1
                say("Please say yes to say your School ID again, or no to continue.") { [weak self] in self?.listen() }
            }
            return
        }

        if isAwaitingLetterPosition {
            handleLetterPosition(lower)
            return
        }
        if isAwaitingLetterValue {
            handleLetterValue(lower)
            return
        }

        if isConfirmingField {
            if FeedbackViewModel.isYes(lower) {
                isConfirmingField = false
                retryCount = 0
                commit(pendingValue)
                if field == .schoolId {
                    Task { await autoCheckSchoolId() }
                } else {
                    moveToNextField()
                }
            } else if FeedbackViewModel.isNo(lower) {
                isConfirmingField = false
                retryCount = 0
                if isNameField {
                    beginLetterCorrection()
                } else {
                    pendingValue = ""
                    say("Okay, please say it again.") { [weak self] in self?.promptCurrentField() }
                }
            } else {
                retryCount += 1
                if retryCount <= Self.maxRetry {
                    say("Please say yes to confirm or no to try again.") { [weak self] in self?.listen() }
                } else {
                    retryCount = 0
                    isConfirmingField = false
                    say("Moving on. Please say it again.") { [weak self] in self?.promptCurrentField() }
                }
            }
            return
        }

        if FeedbackViewModel.isSkip(lower) && (field == .middleName || field == .section) {
            let skipped = field == .middleName ? "Middle name" : "Section"
            say("\(skipped) skipped.") { [weak self] in self?.moveToNextField() }
            return
        }

        voiceStatus = "Heard: \(spoken)"
        confirmField(processVoiceInput(spoken))
    }

    // MARK: - Field values

    private var isNameField: Bool { field == .firstName || field == .middleName || field == .lastName }

    /// processVoiceInput + applyNameCorrection.
    private func processVoiceInput(_ spoken: String) -> String {
        let lower = SpokenInputParser.convertNumberWords(spoken.lowercased().trimmingCharacters(in: .whitespaces))
        switch field {
        case .schoolId: return SpokenInputParser.schoolId(from: lower)
        case .birthdate:
            guard let date = SpokenInputParser.birthdate(from: lower) else { return spoken.trimmingCharacters(in: .whitespaces) }
            return String(format: "%04d-%02d-%02d", date.year, date.month, date.day)
        case .yearLevel: return Self.normalizeYearLevel(lower)
        case .email: return Self.parseSpokenEmail(lower)
        case .firstName: return Self.capitalizeName(nameNormalizer.correctedFirstName(spoken))
        case .middleName, .lastName: return Self.capitalizeName(nameNormalizer.correctedSurname(spoken))
        case .section: return spoken.trimmingCharacters(in: .whitespaces).uppercased()
        }
    }

    private func confirmField(_ value: String) {
        isConfirmingField = true
        pendingValue = value
        voiceStatus = "Confirm: \(value)"
        let message: String
        switch field {
        case .schoolId:
            message = "I heard \(SpokenInputParser.spellDigits(value)) as your school ID. Is that correct? Say yes or no."
        case .firstName, .middleName, .lastName:
            message = "I heard \(Self.spellOut(value)), \(value), as your \(fieldName.lowercased()). Is the spelling correct? Say yes or no."
        case .birthdate:
            let spoken = Self.birthdateFormatter.date(from: value).map { Self.spokenDateFormatter.string(from: $0) } ?? value
            message = "I heard \(spoken) as your birthdate. Is that correct? Say yes or no."
        case .yearLevel:
            message = "I heard \(value) as your year level. Is that correct? Say yes or no."
        case .email:
            message = "I heard your email as \(Self.speakableEmail(value)). Is that correct? Say yes or no."
        case .section:
            message = "I heard \(value). Is that correct? Say yes or no."
        }
        say(message) { [weak self] in self?.listen() }
    }

    private func commit(_ value: String) {
        switch field {
        case .schoolId: schoolId = value
        case .firstName: firstName = value
        case .middleName: middleName = value
        case .lastName: lastName = value
        case .birthdate:
            if let date = Self.birthdateFormatter.date(from: value) { birthdate = date }
        case .yearLevel: yearLevel = value
        case .email: email = value
        case .section: section = value
        }
        voiceStatus = "\(fieldName) saved: \(value)"
    }

    // MARK: - School ID auto-check and final review

    private func autoCheckSchoolId() async {
        guard isVoiceMode else { return }
        voiceStatus = "Checking your School ID..."
        await sayAndWait("Thanks. Let me check if you're already on the school's enrollment list.")
        guard isVoiceMode else { return }
        let found = await checkSchoolId()
        guard isVoiceMode else { return }
        if found {
            detailsFromIdLookup = true
            voiceStatus = "Details loaded. Reviewing..."
            presentFinalReview()
        } else {
            voiceStatus = "No record found for this School ID."
            isAwaitingIdRetryConfirm = true
            retryCount = 0
            field = .firstName
            say("I could not find this School ID on the enrollment list. "
                + "Do you want to say your School ID again? Say yes to try again, or no to continue by voice.") { [weak self] in
                self?.listen()
            }
        }
    }

    private func presentFinalReview() {
        isAwaitingFinalReview = true
        isConfirmingField = false
        retryCount = 0
        voiceStatus = "Reviewing your details..."
        say("Here is a summary of your details. " + reviewSummaryText
            + "Is all of this correct? Say yes to continue, or no to fix something.") { [weak self] in
            self?.listen()
        }
    }

    private var reviewSummaryText: String {
        var text = "First name: \(firstName). "
        if !middleName.isEmpty { text += "Middle name: \(middleName). " }
        text += "Last name: \(lastName). "
        text += "Birthdate: \(birthdateEntered ? Self.spokenDateFormatter.string(from: birthdate) : "not set"). "
        text += "Year level: \(yearLevel). "
        text += "School ID: \(SpokenInputParser.spellDigits(schoolId)). "
        text += "Email: \(Self.speakableEmail(email)). "
        if !section.isEmpty { text += "Section: \(section). " }
        return text
    }

    /// Starts over at the School ID; details autofilled from the wrong
    /// ID's record belong to someone else, so they're cleared.
    private func reaskSchoolId(_ intro: String) {
        schoolId = ""
        if detailsFromIdLookup {
            firstName = ""; middleName = ""; lastName = ""
            yearLevel = ""; email = ""; section = ""
            birthdateEntered = false
        }
        detailsFromIdLookup = false
        field = .schoolId
        pendingValue = ""
        isConfirmingField = false
        say(intro) { [weak self] in self?.promptCurrentField() }
    }

    // MARK: - Letter-by-letter name correction

    private func beginLetterCorrection() {
        isAwaitingLetterPosition = true
        isAwaitingLetterValue = false
        retryCount = 0
        voiceStatus = "Which letter is wrong?"
        say("Which letter is wrong? Please say its position, like letter 1, letter 2, or letter 3. "
            + "Or say start over to say the whole name again.") { [weak self] in self?.listen() }
    }

    private func restartWholeName() {
        isAwaitingLetterPosition = false
        isAwaitingLetterValue = false
        pendingValue = ""
        retryCount = 0
        say("Okay, please say it again.") { [weak self] in self?.promptCurrentField() }
    }

    private static func isStartOver(_ lower: String) -> Bool {
        ["start over", "whole name", "say it again", "from the beginning"].contains { lower.contains($0) }
    }

    private func handleLetterPosition(_ lower: String) {
        if Self.isStartOver(lower) { return restartWholeName() }
        let position = Self.parseLetterPosition(lower, maxLength: pendingValue.count)
        guard position >= 1 else {
            retryCount += 1
            if retryCount <= Self.maxRetry {
                say("I did not catch a valid letter position. Please say a number from 1 to \(pendingValue.count), like letter 1.") { [weak self] in
                    self?.listen()
                }
            } else {
                restartWholeName()
            }
            return
        }
        correctingLetterPosition = position
        retryCount = 0
        isAwaitingLetterPosition = false
        isAwaitingLetterValue = true
        let current = Array(pendingValue)[position - 1].uppercased()
        voiceStatus = "Letter \(position) is \(current). What should it be?"
        say("Letter \(position) is \(current). Please say the correct letter. "
            + "If it should be a doubled letter, say double, then the letter, like double L.") { [weak self] in
            self?.listen()
        }
    }

    private func handleLetterValue(_ lower: String) {
        if Self.isStartOver(lower) { return restartWholeName() }
        let replacement = Self.parseSpokenLetters(lower)
        guard !replacement.isEmpty else {
            retryCount += 1
            if retryCount <= Self.maxRetry {
                say("I did not catch a letter. Please say a single letter, like C. "
                    + "For a doubled letter, say double, then the letter, like double L.") { [weak self] in self?.listen() }
            } else {
                restartWholeName()
            }
            return
        }
        var characters = Array(pendingValue)
        characters.replaceSubrange((correctingLetterPosition - 1)..<correctingLetterPosition, with: Array(replacement))
        let updated = Self.capitalizeName(String(characters))
        isAwaitingLetterValue = false
        retryCount = 0
        voiceStatus = "Updated to: \(updated)"
        confirmField(updated)
    }

    static func parseLetterPosition(_ lower: String, maxLength: Int) -> Int {
        var text = lower
        let ordinals = [("first", "1"), ("second", "2"), ("third", "3"), ("fourth", "4"), ("fifth", "5"),
                        ("sixth", "6"), ("seventh", "7"), ("eighth", "8"), ("ninth", "9"), ("tenth", "10"),
                        ("isa", "1"), ("dalawa", "2"), ("tatlo", "3"), ("apat", "4"), ("lima", "5"),
                        ("anim", "6"), ("pito", "7"), ("walo", "8"), ("siyam", "9"), ("sampu", "10")]
        for (word, digit) in ordinals { text = text.replacingOccurrences(of: word, with: digit) }
        let digits = SpokenInputParser.convertNumberWords(text).filter(\.isNumber)
        guard let value = Int(digits), (1...maxLength).contains(value) else { return -1 }
        return value
    }

    /// "double L" → "LL"; "double u" is the letter W.
    static func parseSpokenLetters(_ lower: String) -> String {
        var text = lower.trimmingCharacters(in: .whitespaces)
        if text == "double u" || text == "double you" { return "W" }
        var repeatCount = 1
        if text.hasPrefix("double ") {
            repeatCount = 2
            text = String(text.dropFirst(7))
        } else if text.hasPrefix("triple ") {
            repeatCount = 3
            text = String(text.dropFirst(7))
        }
        guard let letter = phoneticLetter(text) else { return "" }
        return String(repeating: letter, count: repeatCount)
    }

    private static func phoneticLetter(_ text: String) -> Character? {
        let cleaned = text.filter { $0.isLetter || $0 == " " }.trimmingCharacters(in: .whitespaces)
        guard !cleaned.isEmpty else { return nil }
        let names: [String: Character] = [
            "a": "A", "ay": "A", "b": "B", "bee": "B", "be": "B", "c": "C", "see": "C", "sea": "C",
            "d": "D", "dee": "D", "de": "D", "e": "E", "ee": "E", "f": "F", "eff": "F",
            "g": "G", "gee": "G", "jee": "G", "h": "H", "aitch": "H", "eitch": "H", "i": "I", "eye": "I",
            "j": "J", "jay": "J", "k": "K", "kay": "K", "l": "L", "el": "L", "ell": "L", "m": "M", "em": "M",
            "n": "N", "en": "N", "o": "O", "oh": "O", "p": "P", "pee": "P", "pe": "P",
            "q": "Q", "cue": "Q", "queue": "Q", "r": "R", "ar": "R", "are": "R", "s": "S", "ess": "S",
            "t": "T", "tee": "T", "te": "T", "u": "U", "you": "U", "yu": "U", "v": "V", "vee": "V", "ve": "V",
            "w": "W", "x": "X", "ex": "X", "y": "Y", "why": "Y", "z": "Z", "zee": "Z", "zed": "Z"
        ]
        if let letter = names[cleaned] { return letter }
        let first = Character(cleaned.prefix(1).uppercased())
        return first.isASCII && first.isLetter ? first : nil
    }

    // MARK: - Parsers (parseSpokenEmail / normalizeYearLevel / capitalizeName / spellOut)

    static func parseSpokenEmail(_ input: String) -> String {
        var r = input.trimmingCharacters(in: .whitespaces).lowercased()
        r = r.replacingOccurrences(of: #"^(my email( address)? is |email( address)? is |the email is )"#,
                                   with: "", options: .regularExpression)
        let replacements: [(String, String)] = [
            ("at the rate of ", "@"), ("at the rate ", "@"), ("at sign ", "@"), (" at ", "@"),
            ("dot com ph", ".com.ph"), ("dot edu ph", ".edu.ph"), ("dot com", ".com"), ("dot ph", ".ph"),
            ("dot edu", ".edu"), ("dot net", ".net"), ("dot org", ".org"), ("dot io", ".io"),
            ("dot ", "."), (" period ", "."), (" underscore ", "_"), (" under score ", "_"),
            (" dash ", "-"), (" hyphen ", "-"), (" minus ", "-"), (" space ", "")
        ]
        for (from, to) in replacements { r = r.replacingOccurrences(of: from, with: to) }
        r = r.filter { !$0.isWhitespace }
        if !r.contains("@"), let range = r.range(of: #"at(?=[a-z])"#, options: .regularExpression) {
            r.replaceSubrange(range, with: "@")
        }
        if !r.contains("."), let range = r.range(of: #"dot(?=[a-z])"#, options: .regularExpression) {
            r.replaceSubrange(range, with: ".")
        }
        return r.replacingOccurrences(of: "@@", with: "@").replacingOccurrences(of: "..", with: ".")
    }

    static func normalizeYearLevel(_ input: String) -> String {
        if input.contains("1") || input.contains("first") { return "1st Year" }
        if input.contains("2") || input.contains("second") { return "2nd Year" }
        if input.contains("3") || input.contains("third") { return "3rd Year" }
        if input.contains("4") || input.contains("fourth") { return "4th Year" }
        return capitalizeName(input)
    }

    static func capitalizeName(_ input: String) -> String {
        input.split(whereSeparator: \.isWhitespace)
            .map { $0.prefix(1).uppercased() + $0.dropFirst().lowercased() }
            .joined(separator: " ")
    }

    static func spellOut(_ s: String) -> String {
        s.uppercased().map { $0 == " " ? "space" : String($0) }.joined(separator: ", ")
    }

    static func speakableEmail(_ email: String) -> String {
        email.map { c -> String in
            switch c {
            case "@": return " at "
            case ".": return " dot "
            case "_": return " underscore "
            case "-": return " dash "
            default: return String(c)
            }
        }
        .joined()
        .split(whereSeparator: \.isWhitespace)
        .joined(separator: " ")
    }

    private var fieldName: String {
        switch field {
        case .schoolId: return "School ID"
        case .firstName: return "First Name"
        case .middleName: return "Middle Name"
        case .lastName: return "Last Name"
        case .birthdate: return "Birthdate"
        case .yearLevel: return "Year Level"
        case .email: return "Email"
        case .section: return "Section"
        }
    }

    private var promptForField: String {
        switch field {
        case .schoolId: return "Please say your school ID number."
        case .firstName: return "Please say your first name."
        case .middleName: return "Please say your middle name, or say skip to leave it blank."
        case .lastName: return "Please say your last name."
        case .birthdate: return "Please say your birthdate, including the month, day, and year. For example, January 15, 2005."
        case .yearLevel: return "Please say your year level. For example, first year, second year, third year, or fourth year."
        case .email: return "Please say your email address. Say at for the at symbol, and dot for the period."
        case .section: return "Please say your section, or say skip if you're not sure."
        }
    }

    // MARK: - Speech helpers

    private func say(_ text: String, remember: Bool = true, then action: (() -> Void)? = nil) {
        if remember { lastSpokenInstruction = text }
        stopListening()
        flow += 1
        let token = flow
        Task {
            await tts.speak(text, rateScale: Self.speakingRate, respectsPreferences: false)
            guard token == flow else { return }
            action?()
        }
    }

    private func sayAndWait(_ text: String) async {
        lastSpokenInstruction = text
        stopListening()
        await tts.speak(text, rateScale: Self.speakingRate, respectsPreferences: false)
    }

    private func stopListening() {
        cascade.stop()
        isListening = false
    }
}
