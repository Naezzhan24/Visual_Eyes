import Foundation

/// Wraps `get_student_materials` and the storage signed-URL endpoint.
/// The RPC itself resolves which materials this student can see
/// server-side from the session token — the client never supplies a
/// student id or enrollment filter directly.
struct MaterialsRepository {
    private let client: SupabaseClient

    init(client: SupabaseClient = .shared) {
        self.client = client
    }

    /// Newest first, so `first` is the latest material — HomeFragment
    /// picks the row with the greatest `upload_date` the same way.
    func fetchMaterials(sessionToken: String) async throws -> [LearningMaterial] {
        let materials: [LearningMaterial] = try await client.callRPCArray("get_student_materials", body: [
            "p_session_token": sessionToken
        ])
        return materials.sorted { ($0.uploadDate ?? "") > ($1.uploadDate ?? "") }
    }

    /// Short-lived (1 hour) signed download URL for a material's PDF in
    /// the private "materials" storage bucket. Used by the reader
    /// (Phase 4) — exposed here now since it's part of the same backend
    /// contract as the materials list.
    func signedURL(forFilePath path: String) async throws -> URL {
        try await client.signedMaterialURL(path: path)
    }
}
