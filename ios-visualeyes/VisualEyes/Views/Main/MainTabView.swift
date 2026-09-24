import SwiftUI

enum MainTab: Hashable {
    case home, materials, profile
}

/// Stand-in for MainActivity.java's shared bottom nav hosting
/// Home/Materials/Profile as swappable fragments. `initialTab` matches
/// Android's `startTab` intent extra (routed to "profile" after a
/// retake, "home" otherwise).
///
/// NOTE for later phases: MainActivity also owns pinch-zoom and the
/// triple-tap-anywhere "repeat last instruction" gesture, forwarded into
/// whichever tab is active via the `TabFragment` interface. That shared
/// gesture-forwarding layer isn't built yet — plan is to add it once the
/// on-device reader TTS exists to have something worth "repeating"
/// (Phase 5/6), as a small `ActiveTabVoiceHandler` protocol each tab's
/// view model conforms to.
struct MainTabView: View {
    @State private var selection: MainTab

    init(initialTab: MainTab = .home) {
        _selection = State(initialValue: initialTab)
    }

    var body: some View {
        TabView(selection: $selection) {
            HomeView()
                .tabItem { Label("Home", systemImage: "house.fill") }
                .tag(MainTab.home)
            MaterialsView()
                .tabItem { Label("Materials", systemImage: "books.vertical.fill") }
                .tag(MainTab.materials)
            ProfileView()
                .tabItem { Label("Profile", systemImage: "person.crop.circle.fill") }
                .tag(MainTab.profile)
        }
    }
}

#Preview {
    MainTabView()
        .environment(SessionStore.shared)
}
