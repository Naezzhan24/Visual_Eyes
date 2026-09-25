import SwiftUI

/// Port of `fragment_profile.xml`: Student Information card, Level of
/// Visual Impairment with Retake, Accessibility Options (TTS/STT switches
/// backed by `VoicePreferences`, Assistant voice, Help, Privacy Policy),
/// and Log Out.
struct ProfileView: View {
    @Environment(SessionStore.self) private var sessionStore
    @Environment(TabVoiceController.self) private var voice
    @State private var coordinator = AssessmentCoordinator.shared
    @AppStorage(VoicePreferences.ttsKey) private var ttsEnabled = true
    @AppStorage(VoicePreferences.sttKey) private var sttEnabled = true

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    var body: some View {
        VStack(spacing: 0) {
            VETopBar()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Student Information")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.white)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.bottom, 10)

                    infoCard
                    impairmentCard.padding(.top, 14)
                    VoiceStatusCard(
                        status: voice.status,
                        recognized: voice.recognized,
                        commandsHint: "Available voice commands: Home, Materials, Profile"
                    )
                    .padding(.top, 14)
                    optionsCard.padding(.top, 14)

                    Button("Log Out") { sessionStore.logout() }
                        .buttonStyle(MaroonButtonStyle(height: 55, fontSize: 15))
                        .padding(.top, 18)
                        .padding(.bottom, 6)
                }
                .padding(.horizontal, 18)
                .padding(.top, 10)
                .padding(.bottom, 18)
            }
            .scrollIndicators(.hidden)
            .refreshable { await sessionStore.refreshProfile() }
        }
        .task { await sessionStore.refreshProfile() }
        // Same announcements whether the switch was tapped or spoken.
        .onChange(of: ttsEnabled) { _, on in voice.voicePreferenceChanged(tts: on) }
        .onChange(of: sttEnabled) { _, on in voice.voicePreferenceChanged(stt: on) }
    }

    private var student: Student? { sessionStore.student }

    private var infoCard: some View {
        VStack(spacing: 0) {
            Text(student.map { "\($0.firstName) \($0.lastName)" } ?? "Student Name")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(maxWidth: .infinity)

            Text("Recommended Text Size: \(textSizeLabel)")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(VE.subtitleOnMaroon)
                .multilineTextAlignment(.center)
                .padding(.top, 4)

            Rectangle()
                .fill(Color.white.opacity(0.25))
                .frame(height: 1)
                .padding(.top, 16)
                .padding(.bottom, 14)

            VStack(alignment: .leading, spacing: 10) {
                detailRow("envelope.fill", "Email: \(nonEmpty(student?.email) ?? "No Email")")
                detailRow("info.circle.fill", "Student Number: \(nonEmpty(student?.schoolId) ?? "No Student Number")")
                detailRow("calendar", "Age: \(student?.age.map(String.init) ?? "N/A")")
                detailRow("list.bullet.rectangle", "Year Level: \(nonEmpty(student?.yearLevel) ?? "N/A")")
                detailRow("square.grid.2x2", "Section: \(nonEmpty(student?.section) ?? "N/A")")
            }
        }
        .padding(20)
        .translucentCard()
    }

    private var textSizeLabel: String {
        guard let size = student?.recommendedTextSize else { return "Not set" }
        return "\(Int(size))pt"
    }

    private func detailRow(_ systemImage: String, _ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 15))
                .frame(width: 18, height: 18)
                .accessibilityHidden(true)
            Text(text)
                .font(.system(size: 16))
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(VE.subtitleOnMaroon)
    }

    private var impairmentCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Level of Visual Impairment")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)

            HStack(spacing: 12) {
                Text((nonEmpty(student?.impairmentLevel) ?? "Not assessed").uppercased())
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Button("Retake") { coordinator.pendingRetake = true }
                    .buttonStyle(SoftMaroonButtonStyle(height: 40, fontSize: 13))
                    .frame(width: 96)
                    .accessibilityLabel("Retake assessment")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .translucentCard()
    }

    private var optionsCard: some View {
        VStack(spacing: 10) {
            Text("Accessibility Options")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, 4)

            toggleRow("Text-to-Speech (TTS)", isOn: $ttsEnabled)
            toggleRow("Speech-to-Text (STT)", isOn: $sttEnabled)
            optionRow("Assistant voice", systemImage: "chevron.right") { VoiceSettingsView() }
            optionRow("Help & User Guide", systemImage: "info.circle") { HelpView() }
            optionRow("Privacy Policy", systemImage: "info.circle") { PrivacyPolicyView() }

            Text("VisualED v\(appVersion)")
                .font(.system(size: 12))
                .foregroundStyle(VE.textOnPrimary)
                .padding(.top, 4)
        }
        .padding(18)
        .translucentCard()
    }

    /// `optionTts` / `optionStt`: an option row with a switch.
    private func toggleRow(_ title: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(VE.textPrimary)
        }
        .tint(VE.primary)
        .padding(.horizontal, 14)
        .frame(minHeight: 58)
        .background(optionBackground)
    }

    private var optionBackground: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(Color.white)
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(Color(hex: 0xFFE5E5), lineWidth: 1)
            )
    }

    /// `bg_option_item_light`: white, 18pt corners, faint pink outline.
    private func optionRow<Destination: View>(
        _ title: String,
        systemImage: String,
        @ViewBuilder destination: @escaping () -> Destination
    ) -> some View {
        NavigationLink(destination: destination) {
            HStack {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .semibold))
                    .accessibilityHidden(true)
            }
            .foregroundStyle(VE.textPrimary)
            .padding(.horizontal, 14)
            .frame(minHeight: 58)
            .background(optionBackground)
        }
        .buttonStyle(.plain)
    }

    private func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return value
    }
}

#Preview {
    NavigationStack {
        ProfileView()
            .maroonBackground()
    }
    .environment(SessionStore.shared)
    .environment(TabVoiceController())
}
