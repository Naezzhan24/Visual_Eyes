import Foundation

/// Wraps every auth/registration/profile RPC. Params match the Android
/// app's calls exactly — see AuthManager.java / LoginActivity.java /
/// RegisterActivity.java.
struct AuthRepository {
    private let client: SupabaseClient

    init(client: SupabaseClient = .shared) {
        self.client = client
    }

    /// Password is the student's birthdate, formatted `MM-DD-YYYY` by the
    /// caller — same scheme the Android app uses, this RPC has no separate
    /// "real" password.
    func login(schoolId: String, password: String) async throws -> Student {
        try await client.callRPCFirstRow("student_login", body: [
            "p_school_id": schoolId,
            "p_password": password
        ])
    }

    struct RegisterResult: Decodable {
        let approvalStatus: String
        enum CodingKeys: String, CodingKey { case approvalStatus = "approval_status" }
    }

    func register(
        firstName: String,
        middleName: String?,
        lastName: String,
        birthdateISO: String,
        yearLevel: String,
        schoolId: String,
        email: String,
        section: String?
    ) async throws -> RegisterResult {
        var body: [String: Any] = [
            "p_first_name": firstName,
            "p_middle_name": middleName ?? "",
            "p_last_name": lastName,
            "p_birthdate": birthdateISO,
            "p_year_level": yearLevel,
            "p_school_id": schoolId,
            "p_email": email
        ]
        // Matches Android: section is omitted entirely, not sent blank,
        // when the student didn't provide one.
        if let section, !section.isEmpty {
            body["p_section"] = section
        }
        return try await client.callRPCFirstRow("student_register", body: body)
    }

    /// Autofills the registration form from the pre-imported official
    /// enrollment list — RegisterActivity's "Check School ID" button.
    func lookupEnrolledStudent(schoolId: String) async throws -> EnrolledStudentLookup {
        try await client.callRPCFirstRow("get_enrolled_student_by_school_id", body: [
            "p_school_id": schoolId
        ])
    }

    func fetchProfile(sessionToken: String) async throws -> StudentProfile {
        try await client.callRPCFirstRow("get_student_profile", body: [
            "p_session_token": sessionToken
        ])
    }
}
