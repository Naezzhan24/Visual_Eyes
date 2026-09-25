import SwiftUI

/// Port of `fragment_materials.xml`: header card, the maroon featured
/// "Continue where you left off" card, and the translucent Recent Learning
/// Materials list, with the voice status card. Voice commands ("open
/// [title]", "read titles", …) are handled by `TabVoiceController`.
struct MaterialsView: View {
    @State private var viewModel = MaterialsViewModel()
    @Environment(TabVoiceController.self) private var voice
    @Environment(SessionStore.self) private var sessionStore

    var body: some View {
        VStack(spacing: 0) {
            VETopBar()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    headerCard
                    VoiceStatusCard(
                        status: voice.status,
                        recognized: voice.recognized,
                        commandsHint: "Available voice commands: Home, Materials, Profile, Open featured material"
                    )
                    .padding(.top, 14)

                    sectionLabel("Continue where you left off")
                        .padding(.top, 22)
                    featuredCard
                        .padding(.top, 14)

                    sectionLabel("Recent Learning Materials", size: 16)
                        .padding(.top, 24)
                    recentList
                        .padding(.top, 12)
                }
                .padding(.horizontal, 18)
                .padding(.top, 10)
                .padding(.bottom, 18)
            }
            .scrollIndicators(.hidden)
            .refreshable { await viewModel.loadMaterials() }
        }
        .task { await viewModel.loadMaterials() }
    }

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Welcome to your accessible learning materials.")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white)
                .accessibilityAddTraits(.isHeader)
            Text("Browse and open your latest accessible learning resources with ease.")
                .font(.system(size: 16))
                .foregroundStyle(VE.subtitleOnMaroon)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .translucentCard()
    }

    private func sectionLabel(_ text: String, size: CGFloat = 17) -> some View {
        Text(text)
            .font(.system(size: size, weight: .bold))
            .foregroundStyle(.white)
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private var featuredCard: some View {
        let featured = featuredMaterial
        let card = HStack(spacing: 14) {
            Image(systemName: "books.vertical.fill")
                .font(.system(size: 22))
                .frame(width: 26, height: 26)
            Text(featured?.title ?? "No learning material yet")
                .font(.system(size: 16, weight: .bold))
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.system(size: 18, weight: .semibold))
        }
        .foregroundStyle(VE.textOnPrimary)
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(VE.primary)
                .shadow(color: .black.opacity(0.2), radius: 5, y: 3)
        )

        if let featured {
            NavigationLink(value: featured) { card }
                .buttonStyle(.plain)
        } else {
            card
        }
    }

    /// The last material opened, else the newest (getLastOpened()).
    private var featuredMaterial: LearningMaterial? {
        if let schoolId = sessionStore.student?.schoolId,
           let id = MaterialReadTracker.lastOpenedId(studentSchoolId: schoolId),
           let match = viewModel.materials.first(where: { $0.id == id }) {
            return match
        }
        return viewModel.materials.first
    }

    private var recentList: some View {
        VStack(spacing: 0) {
            if viewModel.isLoading && viewModel.materials.isEmpty {
                ProgressView()
                    .tint(.white)
                    .frame(maxWidth: .infinity, minHeight: 72)
            } else if viewModel.materials.isEmpty {
                Text(viewModel.errorMessage ?? "Materials sent to you will show up here.")
                    .font(.system(size: 16))
                    .foregroundStyle(VE.subtitleOnMaroon)
                    .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                    .padding(.horizontal, 16)
            } else {
                ForEach(viewModel.materials) { material in
                    NavigationLink(value: material) {
                        MaterialCard(material: material)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.vertical, 10)
        .translucentCard()
    }
}

#Preview {
    NavigationStack {
        MaterialsView()
            .maroonBackground()
    }
    .environment(SessionStore.shared)
    .environment(TabVoiceController())
}
