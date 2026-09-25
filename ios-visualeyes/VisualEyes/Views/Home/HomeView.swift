import SwiftUI

/// Port of `fragment_home.xml`: top bar, translucent welcome card, the
/// voice status card, the Font Size Preference card, and the maroon
/// "Learning Materials" card that opens the latest material. Voice
/// commands are handled by the shared `TabVoiceController`.
struct HomeView: View {
    @Binding var selection: MainTab
    @Environment(SessionStore.self) private var sessionStore
    @Environment(TabVoiceController.self) private var voice
    @State private var viewModel = HomeViewModel()
    @State private var showHelp = false
    @AppStorage(FontSizePreferences.useRecommendedKey) private var useRecommendedFont = true
    @AppStorage(FontSizePreferences.recommendedSizeKey) private var recommendedFontSize = FontSizePreferences.defaultSize

    var body: some View {
        VStack(spacing: 0) {
            VETopBar(onHelp: { showHelp = true })

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    welcomeCard
                    VoiceStatusCard(
                        status: voice.status,
                        recognized: voice.recognized,
                        commandsHint: "Available voice commands: Home, Materials, Profile, Open learning material"
                    )
                    fontSizeCard
                    learningMaterialCard
                }
                .padding(.horizontal, 18)
                .padding(.top, 10)
                .padding(.bottom, 18)
            }
            .scrollIndicators(.hidden)
            .refreshable { await viewModel.loadMaterials() }
        }
        .navigationDestination(isPresented: $showHelp) { HelpView() }
        .task { await viewModel.loadMaterials() }
    }

    private var welcomeCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Welcome Back, \(sessionStore.student?.firstName ?? "Student")!")
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(.white)
                .accessibilityAddTraits(.isHeader)
            Text("Ready to continue your accessible learning journey today?")
                .font(.system(size: 16))
                .foregroundStyle(VE.subtitleOnMaroon)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .translucentCard()
    }

    /// `cardFontSizeControl`: the app-wide base text size (FontSizeManager).
    /// Moving the slider saves it as the recommended size, like Android;
    /// the button flips between that and the 14pt default.
    private var fontSizeCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Font Size Preference")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .accessibilityAddTraits(.isHeader)
            Text("Choose whether to use your recommended text size or return to the default text size.")
                .font(.system(size: 16))
                .foregroundStyle(VE.subtitleOnMaroon)

            Text("\(Int(currentFontSize))pt")
                .font(.system(size: currentFontSize, weight: .bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
                .accessibilityHidden(true)

            Slider(
                value: Binding(
                    get: { currentFontSize },
                    set: { newValue in
                        recommendedFontSize = newValue.rounded()
                        useRecommendedFont = true
                    }
                ),
                in: FontSizePreferences.minSize...FontSizePreferences.maxSize,
                step: 1
            )
            .tint(.white)
            .accessibilityLabel("Text size")
            .accessibilityValue("\(Int(currentFontSize)) points")

            Button(useRecommendedFont ? "Use default size (14pt)" : "Use recommended size") {
                useRecommendedFont.toggle()
            }
            .buttonStyle(SoftMaroonButtonStyle(height: 44, fontSize: 15))
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .translucentCard()
    }

    private var currentFontSize: Double {
        useRecommendedFont ? recommendedFontSize : FontSizePreferences.defaultSize
    }

    @ViewBuilder
    private var learningMaterialCard: some View {
        let card = VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "books.vertical.fill")
                    .font(.system(size: 22))
                    .frame(width: 26, height: 26)
                Text("Learning Materials")
                    .font(.system(size: 17, weight: .bold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.system(size: 18, weight: .semibold))
            }
            .foregroundStyle(VE.textOnPrimary)

            Group {
                if viewModel.isLoading && viewModel.materials.isEmpty {
                    ProgressView().tint(.white)
                } else if let featured = viewModel.featuredMaterial {
                    Text(featured.title)
                } else if let error = viewModel.errorMessage {
                    Text(error)
                } else {
                    Text("No learning material yet")
                }
            }
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(VE.textOnPrimary)
            .lineLimit(2)
            .padding(.top, 14)

            Text("Access your latest accessible learning material instantly.")
                .font(.system(size: 16))
                .foregroundStyle(VE.textOnPrimaryMuted)
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(VE.primary)
                .shadow(color: .black.opacity(0.2), radius: 5, y: 3)
        )

        if let featured = viewModel.featuredMaterial {
            NavigationLink(value: featured) { card }
                .buttonStyle(.plain)
                .accessibilityHint("Opens the latest learning material")
        } else {
            Button { selection = .materials } label: { card }
                .buttonStyle(.plain)
        }
    }
}

#Preview {
    NavigationStack {
        HomeView(selection: .constant(.home))
            .maroonBackground()
    }
    .environment(SessionStore.shared)
    .environment(TabVoiceController())
}
