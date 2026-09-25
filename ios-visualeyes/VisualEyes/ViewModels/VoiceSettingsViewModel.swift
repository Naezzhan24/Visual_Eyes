import Foundation
import Observation

/// Stand-in for VoiceSettingsActivity.java: pick 1 of the 4 fixed Google
/// Cloud Neural2 voices, preview before committing, persist via
/// `student_save_voice_settings`.
@MainActor
@Observable
final class VoiceSettingsViewModel {
    var selectedVoice: AssistantVoice = .default
    private(set) var isPreviewing = false
    private(set) var isSaving = false
    private(set) var didSave = false
    var errorMessage: String?

    private let tts: CloudTTSService
    private let voiceSettingsRepository: VoiceSettingsRepository
    private let sessionStore: SessionStore

    init(
        tts: CloudTTSService = .shared,
        voiceSettingsRepository: VoiceSettingsRepository = VoiceSettingsRepository(),
        sessionStore: SessionStore = .shared
    ) {
        self.tts = tts
        self.voiceSettingsRepository = voiceSettingsRepository
        self.sessionStore = sessionStore
    }

    func loadCurrentSelection() async {
        guard let token = sessionStore.sessionToken else { return }
        if let settings = try? await voiceSettingsRepository.fetch(sessionToken: token),
           let voiceId = settings.ttsVoice,
           let voice = AssistantVoice(rawValue: voiceId) {
            selectedVoice = voice
        }
    }

    func preview(_ voice: AssistantVoice) async {
        isPreviewing = true
        await tts.speak("Hi! This is how I sound.", voice: voice, respectsPreferences: false)
        isPreviewing = false
    }

    func keepSelectedVoice() async {
        guard let token = sessionStore.sessionToken else { return }
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }
        do {
            try await voiceSettingsRepository.save(
                sessionToken: token, ttsVoice: selectedVoice.rawValue, speechRateScale: nil
            )
            didSave = true
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
