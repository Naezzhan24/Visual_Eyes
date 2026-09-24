import Foundation

/// Maps onto `student_get_voice_settings`'s response. Not wired into any UI
/// yet (that's Phase 6 — VoiceSettingsView) but the RPC shape is captured
/// now alongside the rest of the backend contract.
struct VoiceSettings: Codable, Equatable {
    let ttsVoice: String?
    let speechRateScale: Double?

    enum CodingKeys: String, CodingKey {
        case ttsVoice = "tts_voice"
        case speechRateScale = "speech_rate_scale"
    }
}

/// The 4 fixed Google Cloud Neural2 voice options, matching
/// TtsVoiceManager.java on the Android side exactly (same voice IDs, so
/// a student's saved preference means the same thing on both apps).
enum AssistantVoice: String, CaseIterable, Identifiable {
    case femaleOne = "en-US-Neural2-F"
    case maleOne = "en-US-Neural2-D"
    case femaleTwo = "en-US-Neural2-C"
    case maleTwo = "en-US-Neural2-J"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .femaleOne: return "Female 1"
        case .maleOne: return "Male 1"
        case .femaleTwo: return "Female 2"
        case .maleTwo: return "Male 2"
        }
    }

    var ssmlGender: String {
        switch self {
        case .femaleOne, .femaleTwo: return "FEMALE"
        case .maleOne, .maleTwo: return "MALE"
        }
    }

    static let `default` = AssistantVoice.femaleOne
}
