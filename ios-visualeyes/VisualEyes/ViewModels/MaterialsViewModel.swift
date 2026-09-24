import Foundation
import Observation

@MainActor
@Observable
final class MaterialsViewModel {
    var materials: [LearningMaterial] = []
    var isLoading = false
    var errorMessage: String?

    private let materialsRepository: MaterialsRepository
    private let sessionStore: SessionStore

    init(materialsRepository: MaterialsRepository = MaterialsRepository(), sessionStore: SessionStore = .shared) {
        self.materialsRepository = materialsRepository
        self.sessionStore = sessionStore
    }

    func loadMaterials() async {
        guard let token = sessionStore.sessionToken else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            materials = try await materialsRepository.fetchMaterials(sessionToken: token)
        } catch let error as SupabaseError {
            if case .sessionExpired = error {
                sessionStore.forceLogout()
            } else {
                errorMessage = error.localizedDescription
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
