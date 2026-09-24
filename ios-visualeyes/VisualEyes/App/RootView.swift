import SwiftUI

/// Top-level router — the SwiftUI equivalent of the Android manifest's
/// navigation shape (IntroActivity → Login/Register → assessment gate →
/// MainActivity). The assessment-vs-main decision itself lives in
/// `AuthenticatedFlowView`.
struct RootView: View {
    @Environment(SessionStore.self) private var sessionStore
    @State private var hasShownIntro = false

    var body: some View {
        Group {
            if sessionStore.isRestoringSession {
                ProgressView()
            } else if !hasShownIntro {
                IntroView(onFinished: { hasShownIntro = true })
            } else if let student = sessionStore.student {
                AuthenticatedFlowView()
                    // Resets the flow's internal state (which stage it's
                    // on) if a different account logs in on the same
                    // install, rather than leaking the previous
                    // student's assessment/tab state.
                    .id(student.sessionToken)
            } else {
                LoginView()
            }
        }
        .animation(.default, value: sessionStore.isLoggedIn)
        .animation(.default, value: hasShownIntro)
    }
}
