import Foundation

/// Wraps `student_save_assessment`. Not wired into any screen yet — the
/// assessment UI itself is built in Phase 3 (TextSizeTestActivity's
/// equivalent) — but the RPC contract is captured now alongside the rest
/// of the backend layer.
struct AssessmentRepository {
    private let client: SupabaseClient

    init(client: SupabaseClient = .shared) {
        self.client = client
    }

    func saveAssessment(
        sessionToken: String,
        impairmentLevel: String,
        recommendedTextSize: Int,
        yesCount: Int,
        noCount: Int
    ) async throws {
        _ = try await client.callRPCString("student_save_assessment", body: [
            "p_session_token": sessionToken,
            "p_impairment_level": impairmentLevel,
            "p_recommended_text_size": recommendedTextSize,
            "p_yes_count": yesCount,
            "p_no_count": noCount
        ])
    }
}
