import Foundation
import Observation

enum LoginError: LocalizedError {
    case invalidCredentials
    case notApproved(status: String)

    var errorDescription: String? {
        switch self {
        case .invalidCredentials:
            return "Incorrect School ID or password. Your password is your birthdate (MM-DD-YYYY)."
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
        } catch SupabaseError.sessionExpired {
            KeychainStore.remove(forKey: sessionTokenKey)
        } catch {
            // Offline or server hiccup: keep the token so the next launch
            // can retry, instead of silently logging the student out.
        }
    }

    func login(schoolId: String, password: String) async throws {
        let loggedIn: Student
        do {
            loggedIn = try await authRepository.login(schoolId: schoolId, password: password)
        } catch SupabaseError.emptyResponse {
            // student_login returns [] (HTTP 200) for a wrong School ID or
            // password, same as LoginActivity's `response.length() == 0`.
            throw LoginError.invalidCredentials
        }
        guard loggedIn.approvalStatus.lowercased() == "approved" else {
            throw LoginError.notApproved(status: loggedIn.approvalStatus)
        }
        guard !loggedIn.sessionToken.isEmpty else {
            throw SupabaseError.emptyResponse
        }
        student = loggedIn
        if let size = loggedIn.recommendedTextSize {
            // Each student starts from their own assessed size on this phone.
            FontSizePreferences.saveRecommendedSize(size)
        }
        lastSchoolId = schoolId
        KeychainStore.set(loggedIn.sessionToken, forKey: sessionTokenKey)
    }

    /// Re-reads the student's details via `get_student_profile`, like
    /// ProfileFragment does on open. `student_login` doesn't return
    /// `year_level`, so this is what fills it in after a fresh login.
    func refreshProfile() async {
        guard let current = student, !current.sessionToken.isEmpty else { return }
        do {
            let profile = try await authRepository.fetchProfile(sessionToken: current.sessionToken)
            student = Student(
                id: current.id,
                firstName: profile.firstName,
                middleName: profile.middleName,
                lastName: profile.lastName,
                age: profile.age ?? current.age,
                schoolId: profile.schoolId,
                email: profile.email,
                impairmentLevel: profile.impairmentLevel,
                recommendedTextSize: profile.recommendedTextSize,
                yearLevel: profile.yearLevel,
                section: profile.section,
                sessionToken: current.sessionToken,
                approvalStatus: current.approvalStatus
            )
        } catch SupabaseError.sessionExpired {
            forceLogout()
        } catch {
            // Offline or transient — keep showing what we already have.
        }
    }

    /// Mirrors a just-saved assessment into the in-memory student so
    /// Profile shows the new level without waiting for a relaunch.
    func applyAssessment(impairmentLevel: String, recommendedTextSize: Double) {
        // TextSizeTestActivity: FontSizeManager.saveRecommendedSize(...)
        FontSizePreferences.saveRecommendedSize(recommendedTextSize)
        guard let current = student else { return }
        student = Student(
            id: current.id,
            firstName: current.firstName,
            middleName: current.middleName,
            lastName: current.lastName,
            age: current.age,
            schoolId: current.schoolId,
            email: current.email,
            impairmentLevel: impairmentLevel,
            recommendedTextSize: recommendedTextSize,
            yearLevel: current.yearLevel,
            section: current.section,
            sessionToken: current.sessionToken,
            approvalStatus: current.approvalStatus
        )
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
