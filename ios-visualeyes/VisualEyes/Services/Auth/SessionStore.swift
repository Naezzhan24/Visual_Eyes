import Foundation
import Observation

enum LoginError: LocalizedError {
    case notApproved(status: String)

    var errorDescription: String? {
        switch self {
        case .notApproved(let status):
            return status == "pending"
                ? "Your account is still waiting for instructor approval."
                : "Your account is not approved yet (status: \(status))."
        }
    }
}

/// Holds the signed-in student + session token in memory, and persists the
/// token in Keychain so a cold launch can restore it. This is the iOS
/// counterpart to AuthManager.java + SessionManager.java combined:
/// AuthManager's storage role (session persistence) and SessionManager's
/// "an RPC just told us the session is dead, force logout" role.
@MainActor
@Observable
final class SessionStore {
    static let shared = SessionStore()

    private(set) var student: Student?
    private(set) var isRestoringSession = true

    /// Set right before a forced logout so the Login screen can show
    /// "your session expired" once, mirroring the extra Android passes
    /// through `SessionManager.forceLogoutAndRedirect`.
    var sessionExpiredMessage: String?

    private let sessionTokenKey = "session_token"
    private let lastSchoolIdKey = "last_school_id"

    private let authRepository: AuthRepository

    var isLoggedIn: Bool { student != nil }
    var sessionToken: String? { student?.sessionToken }

    /// Pre-fills the Login screen's School ID field, surviving logout —
    /// matches AuthManager's `remembered_school_id` behavior.
    var lastSchoolId: String {
        get { UserDefaults.standard.string(forKey: lastSchoolIdKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: lastSchoolIdKey) }
    }

    private init(authRepository: AuthRepository = AuthRepository()) {
        self.authRepository = authRepository
    }

    /// Re-validates a Keychain-cached session token against the backend on
    /// cold launch. If the token is gone or no longer valid, the app just
    /// falls through to the Login screen — no error surfaced, since this
    /// isn't a user-initiated action.
    func restoreSession() async {
        defer { isRestoringSession = false }
        guard let token = KeychainStore.get(forKey: sessionTokenKey) else { return }
        do {
            let profile = try await authRepository.fetchProfile(sessionToken: token)
            student = Student(
                id: nil,
                firstName: profile.firstName,
                middleName: profile.middleName,
                lastName: profile.lastName,
                age: profile.age,
                schoolId: profile.schoolId,
                email: profile.email,
                impairmentLevel: profile.impairmentLevel,
                recommendedTextSize: profile.recommendedTextSize,
                yearLevel: profile.yearLevel,
                section: profile.section,
                sessionToken: token,
                approvalStatus: "approved"
            )
        } catch {
            KeychainStore.remove(forKey: sessionTokenKey)
        }
    }

    func login(schoolId: String, password: String) async throws {
        let loggedIn = try await authRepository.login(schoolId: schoolId, password: password)
        guard loggedIn.approvalStatus == "approved" else {
            throw LoginError.notApproved(status: loggedIn.approvalStatus)
        }
        student = loggedIn
        lastSchoolId = schoolId
        KeychainStore.set(loggedIn.sessionToken, forKey: sessionTokenKey)
    }

    func logout() {
        student = nil
        KeychainStore.remove(forKey: sessionTokenKey)
    }

    /// Called from anywhere an RPC surfaces `SupabaseError.sessionExpired`
    /// — every authenticated screen should route through this rather than
    /// calling `logout()` directly, so the "session expired" message
    /// actually reaches the Login screen.
    func forceLogout(message: String = "Your session expired. Please log in again.") {
        sessionExpiredMessage = message
        logout()
    }
}
