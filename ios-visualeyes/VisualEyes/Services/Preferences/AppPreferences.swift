import Foundation

/// Port of VoicePreferences.java: the Profile screen's Text-to-Speech and
/// Speech-to-Text switches. Stored in UserDefaults under the same keys as
/// Android's `VisualEyesPrefs` so the meaning is identical; views bind to
/// them with `@AppStorage(VoicePreferences.ttsKey)`.
///
/// Like Android's `respectVoicePreferences`, the reader, feedback and
/// assessment screens opt out: they're only usable with voice, so the
/// switches silence the navigation/assistant speech, not those.
enum VoicePreferences {
    static let ttsKey = "tts_enabled"
    static let sttKey = "stt_enabled"

    static var isTtsEnabled: Bool {
        UserDefaults.standard.object(forKey: ttsKey) as? Bool ?? true
    }

    static var isSttEnabled: Bool {
        UserDefaults.standard.object(forKey: sttKey) as? Bool ?? true
    }
}

/// Port of FontSizeManager.java: one app-wide base text size, either the
/// student's recommended size (from the assessment, adjustable with the
/// Home slider) or the 14pt default.
enum FontSizePreferences {
    static let useRecommendedKey = "use_recommended_font"
    static let recommendedSizeKey = "recommended_text_size"

    static let defaultSize: Double = 14
    static let minSize: Double = 14
    static let maxSize: Double = 34

    static var fontSize: Double {
        let defaults = UserDefaults.standard
        let useRecommended = defaults.object(forKey: useRecommendedKey) as? Bool ?? true
        let recommended = defaults.object(forKey: recommendedSizeKey) as? Double ?? defaultSize
        return useRecommended ? recommended : defaultSize
    }

    /// Called after an assessment (TextSizeTestActivity) and when the
    /// Home slider moves; also switches back to "recommended".
    static func saveRecommendedSize(_ size: Double) {
        let clamped = min(max(size, minSize), maxSize)
        UserDefaults.standard.set(clamped, forKey: recommendedSizeKey)
        UserDefaults.standard.set(true, forKey: useRecommendedKey)
    }
}
