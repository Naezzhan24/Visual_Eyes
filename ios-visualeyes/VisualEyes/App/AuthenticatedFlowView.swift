import SwiftUI

/// Owns the post-login routing decision that LoginActivity.java makes on
/// Android: a student with no assessment on file yet (`impairment_level`/
/// `recommended_text_size` both nil on the login RPC's response) sees the
/// assessment before anything else; everyone else goes straight to the
/// main tabs. Also handles "Retake Assessment" from Profile, routed
/// through `AssessmentCoordinator` since Profile is several view layers
/// below this one.
struct AuthenticatedFlowView: View {
    @Environment(SessionStore.self) private var sessionStore
    @State private var coordinator = AssessmentCoordinator.shared
    @State private var stage: Stage = .determining
    @State private var assessmentAttempt = 0

    private enum Stage {
        case determining
        case assessment(isRetake: Bool)
        case result(AssessmentSummary)
        case main(MainTab)
    }

    var body: some View {
        content
            .onChange(of: coordinator.pendingRetake) { _, newValue in
                if newValue {
                    assessmentAttempt += 1
                    stage = .assessment(isRetake: true)
                }
            }
            .task {
                if case .determining = stage { evaluate() }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch stage {
        case .determining:
            ProgressView()

        case .assessment(let isRetake):
            NavigationStack {
                AssessmentView(isRetake: isRetake) { summary in
                    coordinator.pendingRetake = false
                    if let summary {
                        stage = .result(summary)
                    } else if isRetake {
                        // Cancelled a retake — nothing changed, back to
                        // where the student already was.
                        stage = .main(.profile)
                    } else {
                        // Cancelled the mandatory first-time assessment —
                        // there's nowhere else to go yet, so restart it.
                        assessmentAttempt += 1
                        stage = .assessment(isRetake: false)
                    }
                }
            }
            .id(assessmentAttempt)

        case .result(let summary):
            NavigationStack {
                AssessmentResultView(summary: summary) {
                    stage = .main(summary.isRetake ? .profile : .home)
                }
            }

        case .main(let startTab):
            MainTabView(initialTab: startTab)
        }
    }

    private func evaluate() {
        let student = sessionStore.student
        let needsAssessment = student?.impairmentLevel == nil || student?.recommendedTextSize == nil
        stage = needsAssessment ? .assessment(isRetake: false) : .main(.home)
    }
}
