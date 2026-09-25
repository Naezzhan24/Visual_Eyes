import Foundation

/// Thin `URLSession`-based wrapper over the Supabase REST/RPC surface.
///
/// Deliberately NOT the `supabase-swift` SDK: this app's auth is 100%
/// custom Postgres RPCs (see `student_login`/`student_register`), not
/// Supabase Auth, so the SDK's auth/realtime surface would be dead weight.
/// Every authenticated call takes `sessionToken` explicitly as a parameter
/// (mirroring `p_session_token`) rather than being implicit/global, so a
/// session-expiry can be handled right where it's noticed.
actor SupabaseClient {
    static let shared = SupabaseClient()

    private let session: URLSession
    private let decoder: JSONDecoder

    init(session: URLSession = .shared) {
        self.session = session
        self.decoder = JSONDecoder()
    }

    private func makeRequest(url: URL, body: [String: Any]) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(Config.supabaseAnonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(Config.supabaseAnonKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }

    /// Calls a Postgres RPC that returns a JSON array of rows, decoding
    /// the first row. Used by every RPC in this app except the
    /// "true"/"false" save/submit endpoints (see `callRPCString`).
    func callRPCFirstRow<T: Decodable>(_ name: String, body: [String: Any]) async throws -> T {
        let rows: [T] = try await callRPCArray(name, body: body)
        guard let first = rows.first else { throw SupabaseError.emptyResponse }
        return first
    }

    func callRPCArray<T: Decodable>(_ name: String, body: [String: Any]) async throws -> [T] {
        let url = Config.restRPCBaseURL.appendingPathComponent(name)
        let request = try makeRequest(url: url, body: body)
        let (data, response) = try await session.data(for: request)
        try Self.validate(data: data, response: response)
        do {
            return try decoder.decode([T].self, from: data)
        } catch {
            throw SupabaseError.decodingFailed(String(describing: error))
        }
    }

    /// Calls an RPC that returns a bare JSON string, e.g. `"true"`/`"false"`
    /// — the pattern used by `student_save_voice_settings` and
    /// `student_submit_feedback`. Note: per the Android findings, these
    /// return HTTP 200 either way; "false" is the RPC's own way of saying
    /// "rejected" (bad token/value), not an HTTP-level failure.
    func callRPCString(_ name: String, body: [String: Any]) async throws -> String {
        let url = Config.restRPCBaseURL.appendingPathComponent(name)
        let request = try makeRequest(url: url, body: body)
        let (data, response) = try await session.data(for: request)
        try Self.validate(data: data, response: response)
        if let decoded = try? decoder.decode(String.self, from: data) {
            return decoded
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Requests a short-lived signed URL for a file in the private
    /// "materials" storage bucket, mirroring SignedUrlHelper.java.
    func signedMaterialURL(path: String, expiresIn: Int = 3600) async throws -> URL {
        let storagePath = Self.materialStoragePath(from: path)
        guard !storagePath.isEmpty else {
            throw SupabaseError.decodingFailed("Material has no file path")
        }
        let url = Config.storageBaseURL.appendingPathComponent("object/sign/materials/\(storagePath)")
        let request = try makeRequest(url: url, body: ["expiresIn": expiresIn])
        let (data, response) = try await session.data(for: request)
        try Self.validate(data: data, response: response)

        struct SignedURLResponse: Decodable {
            let signedURL: String
            enum CodingKeys: String, CodingKey { case signedURL = "signedURL" }
        }

        let decoded: SignedURLResponse
        do {
            decoded = try decoder.decode(SignedURLResponse.self, from: data)
        } catch {
            throw SupabaseError.decodingFailed(String(describing: error))
        }
        // `signedURL` is "/object/sign/…?token=…", relative to /storage/v1.
        // Resolving it with URL(relativeTo:) would drop "/storage/v1"
        // because of the leading slash, so append it like SignedUrlHelper.
        let base = Config.storageBaseURL.absoluteString
        let suffix = decoded.signedURL.hasPrefix("/") ? decoded.signedURL : "/" + decoded.signedURL
        guard let full = URL(string: base + suffix) else {
            throw SupabaseError.decodingFailed("Signed URL response was not a valid URL")
        }
        return full
    }

    /// Normalizes a stored `file_path` to the raw path inside the
    /// "materials" bucket, matching HomeFragment.buildFileUrl +
    /// SignedUrlHelper.extractStoragePath: accepts full public URLs,
    /// backslashes, leading slashes and a leading "materials/".
    static func materialStoragePath(from stored: String) -> String {
        var path = stored.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\", with: "/")
        let publicPrefix = Config.storageBaseURL.absoluteString + "/object/public/materials/"
        if path.hasPrefix(publicPrefix) {
            path = String(path.dropFirst(publicPrefix.count))
        }
        path = path.removingPercentEncoding ?? path
        while path.hasPrefix("/") { path.removeFirst() }
        if path.hasPrefix("materials/") { path = String(path.dropFirst("materials/".count)) }
        return path
    }

    /// Inspects the HTTP status + body for the error signatures the
    /// Android app already relies on (SQLSTATE 28000 / invalid_session for
    /// expired sessions, unique-constraint names for registration
    /// conflicts) before falling back to a generic HTTP error.
    private static func validate(data: Data, response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { return }
        guard (200...299).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            if body.contains("28000") || body.contains("invalid_session") {
                throw SupabaseError.sessionExpired
            }
            if body.contains("students_email_key") {
                throw SupabaseError.uniqueConstraint("That email is already registered.")
            }
            if body.contains("students_school_id_key") {
                throw SupabaseError.uniqueConstraint("That School ID is already registered.")
            }
            if body.lowercased().contains("duplicate") {
                throw SupabaseError.uniqueConstraint("That account already exists.")
            }
            throw SupabaseError.httpError(statusCode: http.statusCode, body: body)
        }
    }
}
