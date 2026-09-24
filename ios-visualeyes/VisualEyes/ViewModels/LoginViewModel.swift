import Foundation
import Observation

/// Phase 1 covered typed-only login. Phase 2 adds two voice smoke tests
/// on top of it: a spoken welcome prompt (CloudTTSService) and
/// dictating the School ID field by voice (Tier-1 STT via
/// SpeechCascadeSession). LoginActivity's full voice-driven login mode
/// (every field, every confirmation) lands in Phase 5.
@MainActor
@Observable
final class LoginViewModel {
    var schoolId: String = ""
    var password: String = ""
    var isLoading = false
    var errorMessage: String?
    var isListeningForSchoolId = false

    private let sessionStore: SessionStore
    private let cascade: SpeechCascadeSession
    private let micPermission: MicPermissionService
    private let tts: CloudTTSService

    init(
        sessionStore: SessionStore = .shared,
        cascade: SpeechCascadeSession = SpeechCascadeSession(),
        micPermission: MicPermissionService = .shared,
        tts: CloudTTSService = .shared
    ) {
        self.sessionStore = sessionStore
        self.cascade = cascade
        self.micPermission = micPermission
        self.tts = tts
        self.schoolId = sessionStore.lastSchoolId
    }

    var canSubmit: Bool {
        !schoolId.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty && !isLoading
    }

    func login() async {
        guard canSubmit else { return }
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
        }
    }

    /// Phase 2 smoke test for the Cloud TTS path.
    func speakWelcomePrompt() async {
        await tts.speak("Welcome back. Please enter your School ID and password to log in.")
    }

    /// Phase 2 smoke test for the Tier-1 STT path: toggles voice
    /// dictation of the School ID field.
    func toggleSchoolIdDictation() async {
        if isListeningForSchoolId {
            cascade.stop()
            isListeningForSchoolId = false
            return
        }

        guard await micPermission.requestIfNeeded() else {
            errorMessage = "Microphone/speech permission is needed to use voice input. You can enable it in Settings."
            return
        }

        isListeningForSchoolId = true
        errorMessage = nil
        cascade.listen(contextualStrings: ["school ID"]) { [weak self] event in
            guard let self else { return }
            switch event {
            case .partial(let text):
                self.schoolId = text
            case .final(let text):
                self.schoolId = text
                self.isListeningForSchoolId = false
            case .error(let message):
                self.errorMessage = message
                self.isListeningForSchoolId = false
            case .timedOut:
                self.isListeningForSchoolId = false
            }
        }
    }
}
