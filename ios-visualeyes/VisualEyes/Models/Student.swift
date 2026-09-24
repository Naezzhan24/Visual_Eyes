import Foundation

/// Maps directly onto the row shape returned by the `student_login` RPC
/// (see AuthManager.java / LoginActivity.java on the Android side).
struct Student: Codable, Equatable {
    let id: Int?
    let firstName: String
    let middleName: String?
    let lastName: String
    let age: Int?
    let schoolId: String
    let email: String?
    let impairmentLevel: String?
    let recommendedTextSize: Double?
    let yearLevel: String?
    let section: String?
    let sessionToken: String
    let approvalStatus: String

    enum CodingKeys: String, CodingKey {
        case id
        case firstName = "first_name"
        case middleName = "middle_name"
        case lastName = "last_name"
        case age
        case schoolId = "school_id"
        case email
        case impairmentLevel = "impairment_level"
        case recommendedTextSize = "recommended_text_size"
        case yearLevel = "year_level"
        case section
        case sessionToken = "session_token"
        case approvalStatus = "approval_status"
    }
}

/// Maps onto `get_student_profile`'s response shape — a narrower set of
/// fields than the login row, with no session token of its own (the caller
/// already holds one).
struct StudentProfile: Codable, Equatable {
    let firstName: String
    let middleName: String?
    let lastName: String
    let schoolId: String
    let email: String?
    let age: Int?
    let yearLevel: String?
    let section: String?
    let impairmentLevel: String?
    let recommendedTextSize: Double?

    enum CodingKeys: String, CodingKey {
        case firstName = "first_name"
        case middleName = "middle_name"
        case lastName = "last_name"
        case schoolId = "school_id"
        case email
        case age
        case yearLevel = "year_level"
        case section
        case impairmentLevel = "impairment_level"
        case recommendedTextSize = "recommended_text_size"
    }
}

/// Maps onto `get_enrolled_student_by_school_id`'s response — used to
/// autofill the registration form from the pre-imported official
/// enrollment list, mirroring RegisterActivity's "Check School ID" button.
struct EnrolledStudentLookup: Codable, Equatable {
    let firstName: String
    let middleName: String?
    let lastName: String
    let birthdate: String
    let yearLevel: String?
    let email: String?
    let section: String?

    enum CodingKeys: String, CodingKey {
        case firstName = "first_name"
        case middleName = "middle_name"
        case lastName = "last_name"
        case birthdate
        case yearLevel = "year_level"
        case email
        case section
    }
}
