import Foundation

/// Wraps `student_get_voice_settings` / `student_save_voice_settings`.
/// Not wired into any screen yet (Phase 6 — VoiceSettingsView), captured
/// now for backend-contract completeness.
struct VoiceSettingsRepository {
    private let client: SupabaseClient

    init(client: SupabaseClient = .shared) {
        self.client = client
    }

    func fetch(sessionToken: String) async throws -> VoiceSettings {
        try await client.callRPCFirstRow("student_get_voice_settings", body: [
            "p_session_token": sessionToken
        ])
    }

    /// Either param can be left `nil` to leave that value unchanged
    /// server-side, matching `p_tts_voice`/`p_speech_rate_scale`'s
    /// nullable behavior on the Android side.
    @discardableResult
    func save(sessionToken: String, ttsVoice: String?, speechRateScale: Double?) async throws -> Bool {
        var body: [String: Any] = ["p_session_token": sessionToken]
        // `.map { $0 as Any }` before `??` is needed because `String?`/
        // `Double?` and `NSNull` aren't directly unifiable under `??` —
        // this coerces to `Any?` first so NSNull (JSON null) is a valid
        // fallback.
        body["p_tts_voice"] = ttsVoice.map { $0 as Any } ?? NSNull()
        body["p_speech_rate_scale"] = speechRateScale.map { $0 as Any } ?? NSNull()
        let result = try await client.callRPCString("student_save_voice_settings", body: body)
        return result == "true"
    }
}
