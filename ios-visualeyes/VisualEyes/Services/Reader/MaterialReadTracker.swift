import Foundation

/// Persists the last-read chunk index per student per material, so
/// reopening a material resumes where the student left off — direct
/// analogue of `MaterialReadTracker.java`. Keyed by School ID rather than
/// the numeric student id, since `Student.id` isn't always populated
/// (the profile-fetch RPC doesn't return it) while School ID always is.
enum MaterialReadTracker {
    private static func key(studentSchoolId: String, materialId: Int) -> String {
        "reader_chunk_index_\(studentSchoolId)_\(materialId)"
    }

    static func lastChunkIndex(studentSchoolId: String, materialId: Int) -> Int {
        UserDefaults.standard.integer(forKey: key(studentSchoolId: studentSchoolId, materialId: materialId))
    }

    static func saveChunkIndex(_ index: Int, studentSchoolId: String, materialId: Int) {
        UserDefaults.standard.set(index, forKey: key(studentSchoolId: studentSchoolId, materialId: materialId))
    }
}
