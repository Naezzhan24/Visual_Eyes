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

    // MARK: Opened / New — drives the Sent Materials drawer's dot and
    // "Unread first" sort (MaterialReadTracker.isOpened/markOpened).

    private static func openedKey(studentSchoolId: String) -> String {
        "opened_materials_\(studentSchoolId)"
    }

    static func isOpened(materialId: Int, studentSchoolId: String) -> Bool {
        let opened = UserDefaults.standard.array(forKey: openedKey(studentSchoolId: studentSchoolId)) as? [Int] ?? []
        return opened.contains(materialId)
    }

    /// The material opened most recently — MaterialsFragment's
    /// `getLastOpened()`, used as the "featured" material.
    static func lastOpenedId(studentSchoolId: String) -> Int? {
        UserDefaults.standard.object(forKey: "last_opened_material_\(studentSchoolId)") as? Int
    }

    static func markOpened(materialId: Int, studentSchoolId: String) {
        UserDefaults.standard.set(materialId, forKey: "last_opened_material_\(studentSchoolId)")
        let key = openedKey(studentSchoolId: studentSchoolId)
        var opened = UserDefaults.standard.array(forKey: key) as? [Int] ?? []
        guard !opened.contains(materialId) else { return }
        opened.append(materialId)
        UserDefaults.standard.set(opened, forKey: key)
    }
}
