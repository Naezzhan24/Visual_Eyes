import SwiftUI

enum MainTab: Hashable, CaseIterable {
    case home, materials, profile

    var title: String {
        switch self {
        case .home: "Home"
        case .materials: "Materials"
        case .profile: "Profile"
        }
    }

    var systemImage: String {
        switch self {
        case .home: "house.fill"
        case .materials: "books.vertical.fill"
        case .profile: "person.crop.circle.fill"
        }
    }
}

/// Port of MainActivity.java + `activity_main.xml`: the maroon gradient
/// with Home/Materials/Profile swapped above the floating white bottom-nav
/// card. `initialTab` matches Android's `startTab` intent extra (routed to
/// "profile" after a retake, "home" otherwise).
///
/// One NavigationStack wraps the tabs so pushed screens (reader, Help, …)
/// cover the bottom nav the way Android's separate activities do.
///
/// Also owns what MainActivity owned: the voice assistant shared by the
/// three tabs (`TabVoiceController`), triple-tap-anywhere to repeat the
/// last instruction, and pinch-to-zoom (1×–3×) on the tab content.
struct MainTabView: View {
    @Environment(SessionStore.self) private var sessionStore
    @State private var selection: MainTab
    @State private var path = NavigationPath()
    @State private var isDrawerOpen = false
    @State private var voice = TabVoiceController()
    @State private var hasGreeted = false
    @State private var zoom: CGFloat = 1
    @GestureState private var pinch: CGFloat = 1

    /// Screens a voice command can push besides a material.
    enum Route: Hashable {
        case voiceSettings
        case feedback(LearningMaterial)
    }

    init(initialTab: MainTab = .home) {
        _selection = State(initialValue: initialTab)
    }

    var body: some View {
        NavigationStack(path: $path) {
            ZStack {
                tabs
                SentMaterialsDrawer(isOpen: $isDrawerOpen) { material in
                    path.append(material)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: LearningMaterial.self) { material in
                MaterialReaderView(material: material)
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .voiceSettings: VoiceSettingsView()
                case .feedback(let material): FeedbackView(material: material)
                }
            }
        }
        .environment(\.openMaterialsDrawer) { isDrawerOpen = true }
        .environment(voice)
        .onAppear { voice.navigate = handle }
    }

    private var tabs: some View {
        VStack(spacing: 0) {
            ZStack {
                switch selection {
                case .home: HomeView(selection: $selection)
                case .materials: MaterialsView()
                case .profile: ProfileView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .scaleEffect(min(max(zoom * pinch, 1), 3))
            .clipped()
            .animation(.easeInOut(duration: 0.2), value: selection)
            .simultaneousGesture(
                MagnificationGesture()
                    .updating($pinch) { value, state, _ in state = value }
                    .onEnded { value in
                        let next = min(max(zoom * value, 1), 3)
                        // Snap back to 100% near the bottom, like MainActivity.
                        zoom = next < 1.05 ? 1 : next
                    }
            )

            BottomNavBar(selection: $selection) {
                voice.sameTabTapped()
            }
        }
        .maroonBackground()
        // Triple-tap anywhere repeats the last spoken instruction.
        .simultaneousGesture(TapGesture(count: 3).onEnded { voice.repeatLastInstruction() })
        // Keep VoiceOver out of the tabs while the drawer covers them.
        .accessibilityHidden(isDrawerOpen)
        .accessibilityAction(named: "Repeat last instruction") { voice.repeatLastInstruction() }
        .onAppear {
            voice.activate(tab: selection, greet: !hasGreeted)
            hasGreeted = true
        }
        .onDisappear { voice.deactivate() }
        .onChange(of: selection) { _, tab in voice.tabChanged(to: tab) }
        .onChange(of: isDrawerOpen) { _, open in
            // The drawer is its own screen for voice purposes.
            if open { voice.deactivate() } else { voice.activate(tab: selection, greet: false) }
        }
    }

    private func handle(_ navigation: TabVoiceNavigation) {
        switch navigation {
        case .switchTab(let tab): selection = tab
        case .openMaterial(let material): path.append(material)
        case .feedback(let material): path.append(Route.feedback(material))
        case .voiceSettings: path.append(Route.voiceSettings)
        case .retakeAssessment: AssessmentCoordinator.shared.pendingRetake = true
        case .logout: sessionStore.logout()
        }
    }
}

/// `bottomNavCard`: white 26pt-radius card, three equal items, the active
/// one on the `bg_nav_active_maroon` gradient.
private struct BottomNavBar: View {
    @Binding var selection: MainTab
    /// Tapping the tab you're already on announces where you are.
    var onReselect: () -> Void = {}

    var body: some View {
        HStack(spacing: 0) {
            ForEach(MainTab.allCases, id: \.self) { tab in
                item(tab)
            }
        }
        .padding(.horizontal, 6)
        .frame(height: 80)
        .background(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(VE.surface)
                .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    private func item(_ tab: MainTab) -> some View {
        let isActive = selection == tab
        return Button {
            if isActive { onReselect() } else { selection = tab }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: 22))
                    .frame(height: 26)
                Text(tab.title)
                    .font(.system(size: 12, weight: .bold))
            }
            .foregroundStyle(isActive ? VE.textOnPrimary : VE.primary)
            .frame(maxWidth: .infinity)
            .frame(height: 60)
            .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(VE.buttonGradient)
                    .opacity(isActive ? 1 : 0)
            )
            .padding(6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.title)
        .accessibilityAddTraits(isActive ? .isSelected : [])
    }
}

#Preview {
    MainTabView()
        .environment(SessionStore.shared)
}
