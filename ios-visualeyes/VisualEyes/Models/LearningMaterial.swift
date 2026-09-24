import Foundation

/// Maps onto one row of `get_student_materials`'s response.
struct LearningMaterial: Codable, Identifiable, Hashable {
    let id: Int
    let title: String
    let filePath: String
    let uploadDate: String?

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case filePath = "file_path"
        case uploadDate = "upload_date"
    }
}
