import XCTest
@testable import VisualEyes

/// Sanity-checks that the Codable models line up with the actual RPC
/// response shapes (snake_case JSON keys) documented in the plan, not
/// just with whatever field names felt natural to write in Swift.
final class ModelDecodingTests: XCTestCase {
    func testStudentDecodesFromLoginRPCShape() throws {
        let json = """
        {
            "id": 42,
            "first_name": "Juan",
            "middle_name": "Reyes",
            "last_name": "Dela Cruz",
            "age": 16,
            "school_id": "2024-0001",
            "email": "juan@example.com",
            "impairment_level": "Moderate Visual Impairment Support Needed",
            "recommended_text_size": 30,
            "year_level": "Grade 10",
            "section": "Sampaguita",
            "session_token": "abc123",
            "approval_status": "approved"
        }
        """.data(using: .utf8)!

        let student = try JSONDecoder().decode(Student.self, from: json)
        XCTAssertEqual(student.firstName, "Juan")
        XCTAssertEqual(student.schoolId, "2024-0001")
        XCTAssertEqual(student.sessionToken, "abc123")
        XCTAssertEqual(student.approvalStatus, "approved")
    }

    func testLearningMaterialDecodesFromRPCShape() throws {
        let json = """
        {"id": 7, "title": "Intro to Photosynthesis", "file_path": "materials/7.pdf", "upload_date": "2026-09-01"}
        """.data(using: .utf8)!

        let material = try JSONDecoder().decode(LearningMaterial.self, from: json)
        XCTAssertEqual(material.id, 7)
        XCTAssertEqual(material.filePath, "materials/7.pdf")
    }

    func testAssistantVoiceIDsMatchAndroidTtsVoiceManager() {
        // These four IDs must stay byte-identical to TtsVoiceManager.java's
        // OPTIONS so a student's saved voice preference means the same
        // thing whether it was set from the Android app or this one.
        let ids = Set(AssistantVoice.allCases.map(\.rawValue))
        XCTAssertEqual(ids, [
            "en-US-Neural2-F",
            "en-US-Neural2-D",
            "en-US-Neural2-C",
            "en-US-Neural2-J"
        ])
    }
}
