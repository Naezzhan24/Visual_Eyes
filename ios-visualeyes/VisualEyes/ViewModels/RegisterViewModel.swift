import Foundation
import Observation

/// Phase 1: typed-only registration. RegisterActivity.java's voice-guided
/// entry (spoken field-by-field confirmation, fuzzy name correction via
/// NameNormalizer, spoken Privacy Policy agreement) is layered on in
/// Phase 5, once the STT cascade and NameNormalizer/TextSimilarity ports
/// exist. This covers the same fields and the same backend contract.
@MainActor
@Observable
final class RegisterViewModel {
    var firstName = ""
    var middleName = ""
    var lastName = ""
    var birthdate = Date()
    var yearLevel = ""
    var schoolId = ""
    var email = ""
    var section = ""

    var isCheckingSchoolId = false
    var isSubmitting = false
    var errorMessage: String?
    var successMessage: String?

    private let authRepository: AuthRepository

    init(authRepository: AuthRepository = AuthRepository()) {
        self.authRepository = authRepository
    }

    private static let birthdateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd-yyyy"
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter
    }()

    var canSubmit: Bool {
        !firstName.trimmingCharacters(in: .whitespaces).isEmpty
            && !lastName.trimmingCharacters(in: .whitespaces).isEmpty
            && !yearLevel.trimmingCharacters(in: .whitespaces).isEmpty
            && !schoolId.trimmingCharacters(in: .whitespaces).isEmpty
            && !email.trimmingCharacters(in: .whitespaces).isEmpty
            && !isSubmitting
    }

    /// Autofills name/birthdate/year level/email/section from the official
    /// enrollment list — mirrors RegisterActivity's "Check School ID"
    /// button.
    func checkSchoolId() async {
        let trimmed = schoolId.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        isCheckingSchoolId = true
        errorMessage = nil
        defer { isCheckingSchoolId = false }
        do {
            let match = try await authRepository.lookupEnrolledStudent(schoolId: trimmed)
            firstName = match.firstName
            middleName = match.middleName ?? ""
            lastName = match.lastName
            if let date = Self.birthdateFormatter.date(from: match.birthdate) {
                birthdate = date
            }
            yearLevel = match.yearLevel ?? ""
            email = match.email ?? ""
            section = match.section ?? ""
        } catch {
            errorMessage = "Could not find that School ID on the enrollment list."
        }
    }

    /// Returns `true` on success so the view can dismiss/navigate back.
    func submit() async -> Bool {
        guard canSubmit else { return false }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }
        do {
            let result = try await authRepository.register(
                firstName: firstName.trimmingCharacters(in: .whitespaces),
                middleName: middleName.isEmpty ? nil : middleName,
                lastName: lastName.trimmingCharacters(in: .whitespaces),
                birthdateMMDDYYYY: Self.birthdateFormatter.string(from: birthdate),
                yearLevel: yearLevel.trimmingCharacters(in: .whitespaces),
                schoolId: schoolId.trimmingCharacters(in: .whitespaces),
                email: email.trimmingCharacters(in: .whitespaces),
                section: section.isEmpty ? nil : section
            )
            successMessage = result.approvalStatus == "approved"
                ? "Registered! You can log in now."
                : "Registered. Your account is pending instructor approval."
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }
}
