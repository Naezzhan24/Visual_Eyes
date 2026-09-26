import SwiftUI

@main
struct VisualEyesApp: App {
    @State private var sessionStore = SessionStore.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(sessionStore)
                .task {
                    await sessionStore.restoreSession()
                }
                .task {
                    // Warm up the offline Whisper model once the app has
                    // settled, so the first offline answer isn't slow.
                    _ = NetworkMonitor.shared
                    try? await Task.sleep(for: .seconds(5))
                    WhisperSTTService.shared.preload()
                }
        }
        .onChange(of: scenePhase) { _, newPhase in
            // Re-check mic/speech permission on every foreground, not
            // just at first request — fixes the Android gap where a
            // Settings-granted permission wasn't noticed until the next
            // cold launch.
            if newPhase == .active {
                MicPermissionService.shared.refresh()
            }
        }
    }
}
