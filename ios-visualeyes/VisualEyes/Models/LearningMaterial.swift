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

extension LearningMaterial {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientInt(.id) else {
            throw DecodingError.keyNotFound(CodingKeys.id, .init(codingPath: c.codingPath, debugDescription: "Material row has no id"))
        }
        self.id = id
        title = c.lenientString(.title) ?? "Learning Material"
        filePath = c.lenientString(.filePath) ?? ""
        uploadDate = c.lenientString(.uploadDate)
    }
}
