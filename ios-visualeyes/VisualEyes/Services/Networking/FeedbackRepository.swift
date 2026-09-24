import Foundation

/// Wraps `student_submit_feedback`. Not wired into any screen yet
/// (Phase 6 — FeedbackFlowView), captured now for backend-contract
/// completeness.
///
/// IMPORTANT for whoever builds Phase 6: `feedbackText` must be built
/// with the *exact* blob format FeedbackActivity.java uses today
/// (Satisfaction/Text Size/Reading Speed/Material Feedback/Instructor
/// Feedback, newline-joined, specific per-field phrasing) — see the plan
/// doc for the verbatim template. There's no separate column per
/// sub-answer on the backend, just this one text blob plus the rating.
struct FeedbackRepository {
    private let client: SupabaseClient

    init(client: SupabaseClient = .shared) {
        self.client = client
    }

    @discardableResult
    func submit(sessionToken: String, materialId: Int, rating: Int, feedbackText: String) async throws -> Bool {
        let result = try await client.callRPCString("student_submit_feedback", body: [
            "p_session_token": sessionToken,
            "p_material_id": materialId,
            "p_rating": rating,
            "p_feedback_text": feedbackText
        ])
        return result == "true"
    }
}
