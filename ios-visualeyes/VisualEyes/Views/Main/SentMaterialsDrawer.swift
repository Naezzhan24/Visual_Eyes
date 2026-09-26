import SwiftUI

/// Port of the "Sent Materials" drawer in `activity_main.xml` +
/// MaterialsDrawerController.java: slides in from the leading edge over
/// every tab, lists every material with a New/Opened dot, and sorts by
/// newest, oldest, unread first or title (remembered between launches).
struct SentMaterialsDrawer: View {
    @Binding var isOpen: Bool
    var onOpen: (LearningMaterial) -> Void

    @Environment(SessionStore.self) private var sessionStore
    @State private var viewModel = MaterialsViewModel()
    @AppStorage("materials_drawer_sort_mode") private var sortRaw = SortMode.newest.rawValue

    enum SortMode: Int, CaseIterable {
        case newest, oldest, unreadFirst, titleAZ

        var title: String {
            switch self {
            case .newest: "Newest first"
            case .oldest: "Oldest first"
            case .unreadFirst: "Unread first"
            case .titleAZ: "Title A to Z"
            }
        }
    }

    private var sort: SortMode { SortMode(rawValue: sortRaw) ?? .newest }
    private var schoolId: String { sessionStore.student?.schoolId ?? "" }

    var body: some View {
        ZStack(alignment: .leading) {
            if isOpen {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture { close() }
                    .accessibilityLabel("Close Sent Materials")
                    .accessibilityAddTraits(.isButton)
                    .transition(.opacity)

                panel
                    .transition(.move(edge: .leading))
            }
        }
        .animation(.easeOut(duration: 0.25), value: isOpen)
    }

    private var panel: some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    list
                }
                .padding(.bottom, 12)
            }
            .refreshable { await viewModel.loadMaterials() }
        }
        .frame(width: 300)
        .frame(maxHeight: .infinity)
        .background(VE.background.ignoresSafeArea())
        .shadow(color: .black.opacity(0.3), radius: 12)
        .gesture(
            DragGesture().onEnded { value in
                if value.translation.width < -60 { close() }
            }
        )
        .task { await viewModel.loadMaterials() }
        .accessibilityAddTraits(.isModal)
    }

    private var header: some View {
        HStack(spacing: 4) {
            Text("Sent Materials")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)

            Menu {
                Picker("Sort", selection: $sortRaw) {
                    ForEach(SortMode.allCases, id: \.rawValue) { mode in
                        Text(mode.title).tag(mode.rawValue)
                    }
                }
            } label: {
                Image(systemName: "arrow.up.arrow.down")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
            }
            .accessibilityLabel("Sort materials, currently \(sort.title)")

            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
            }
            .accessibilityLabel("Close")
        }
        .padding(.leading, 20)
        .padding(.trailing, 12)
        .frame(height: 72)
        .background(VE.headerGradient.ignoresSafeArea(edges: .top))
    }

    @ViewBuilder
    private var list: some View {
        if viewModel.isLoading && viewModel.materials.isEmpty {
            ProgressView()
                .tint(VE.primary)
                .frame(maxWidth: .infinity)
                .padding(24)
        } else if viewModel.materials.isEmpty {
            Text(viewModel.errorMessage ?? "No materials sent yet.")
                .font(.system(size: 14))
                .foregroundStyle(VE.textSecondary)
                .padding(16)
        } else {
            let sorted = sortedMaterials
            let showHeaders = sort == .newest || sort == .oldest
            ForEach(Array(sorted.enumerated()), id: \.element.id) { index, material in
                if showHeaders {
                    let group = Self.dateGroupLabel(material.uploadDate)
                    if index == 0 || Self.dateGroupLabel(sorted[index - 1].uploadDate) != group {
                        Text(group)
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(VE.primary)
                            .padding(.horizontal, 16)
                            .padding(.top, 14)
                            .padding(.bottom, 6)
                            .accessibilityAddTraits(.isHeader)
                    }
                }
                row(material)
            }
        }
    }

    private func row(_ material: LearningMaterial) -> some View {
        let opened = MaterialReadTracker.isOpened(materialId: material.id, studentSchoolId: schoolId)
        return Button {
            close()
            onOpen(material)
        } label: {
            HStack(spacing: 12) {
                Circle()
                    .fill(opened ? Color(hex: 0xCCCCCC) : VE.primary)
                    .frame(width: 10, height: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(material.title)
                        .font(.system(size: 20))
                        .foregroundStyle(VE.textBody)
                        .multilineTextAlignment(.leading)
                    Text(opened ? "Opened" : "New")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(opened ? Color(hex: 0x9E9E9E) : VE.primary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.white)
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(VE.divider, lineWidth: 1)
                    )
            )
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(material.title), \(opened ? "opened" : "new")")
        .accessibilityAddTraits(.isButton)
    }

    private var sortedMaterials: [LearningMaterial] {
        let materials = viewModel.materials
        switch sort {
        case .newest:
            return materials.sorted { ($0.uploadDate ?? "") > ($1.uploadDate ?? "") }
        case .oldest:
            return materials.sorted { ($0.uploadDate ?? "") < ($1.uploadDate ?? "") }
        case .unreadFirst:
            return materials.sorted { a, b in
                let aOpened = MaterialReadTracker.isOpened(materialId: a.id, studentSchoolId: schoolId)
                let bOpened = MaterialReadTracker.isOpened(materialId: b.id, studentSchoolId: schoolId)
                if aOpened != bOpened { return !aOpened }
                return (a.uploadDate ?? "") > (b.uploadDate ?? "")
            }
        case .titleAZ:
            return materials.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        }
    }

    private func close() {
        isOpen = false
    }

    private static let isoDay: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    private static let longDay: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMMM d, yyyy"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    /// "Today", "Yesterday" or "September 25, 2026" — dateGroupLabel().
    static func dateGroupLabel(_ raw: String?) -> String {
        guard let raw, !raw.trimmingCharacters(in: .whitespaces).isEmpty else { return "Unknown Date" }
        let dayPart = String(raw.prefix(10))
        guard let date = isoDay.date(from: dayPart) else { return dayPart }
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        return longDay.string(from: date)
    }
}

// MARK: - Opening the drawer from any tab's top bar

private struct OpenDrawerKey: EnvironmentKey {
    static let defaultValue: (() -> Void)? = nil
}

extension EnvironmentValues {
    /// Set by MainTabView; tabs hand it to `VETopBar(onMenu:)`.
    var openMaterialsDrawer: (() -> Void)? {
        get { self[OpenDrawerKey.self] }
        set { self[OpenDrawerKey.self] = newValue }
    }
}
