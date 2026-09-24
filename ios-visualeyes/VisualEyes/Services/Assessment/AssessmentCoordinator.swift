import Foundation
import Observation

/// Lets Profile's "Retake Assessment" action (deep inside `MainTabView`)
/// tell `AuthenticatedFlowView` (which owns whether Assessment or the
/// main tabs is on screen) to switch into the assessment flow, without
/// threading a callback through every intermediate view.
@MainActor
@Observable
final class AssessmentCoordinator {
    static let shared = AssessmentCoordinator()
    var pendingRetake = false
    private init() {}
}
