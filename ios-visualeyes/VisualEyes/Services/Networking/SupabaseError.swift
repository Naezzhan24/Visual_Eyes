import Foundation

enum SupabaseError: Error, LocalizedError, Equatable {
    /// Server reported SQLSTATE 28000 / "invalid_session" — the session
    /// token is expired or revoked. Mirrors SessionManager.java's check.
    case sessionExpired
    case httpError(statusCode: Int, body: String)
    case decodingFailed(String)
    case emptyResponse
    /// A unique-constraint violation from student_register, with a
    /// human-readable message already chosen (e.g. "email already used").
    case uniqueConstraint(String)

    var errorDescription: String? {
        switch self {
        case .sessionExpired:
            return "Your session has expired. Please log in again."
        case .httpError(let code, let body):
            return "Request failed (\(code)): \(body)"
        case .decodingFailed:
            return "Could not read the server's response."
        case .emptyResponse:
            return "The server returned no data."
        case .uniqueConstraint(let message):
            return message
        }
    }
}
