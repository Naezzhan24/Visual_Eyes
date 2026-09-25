import SwiftUI

/// Port of `activity_voice_settings.xml`: white card of four maroon voice
/// buttons (the chosen one turns blue), a status chip, then the blue
/// "Keep this voice" and yellow "Cancel" buttons. Tapping a voice selects
/// and previews it, like VoiceSettingsActivity.
struct VoiceSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = VoiceSettingsViewModel()

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                card

                Text(statusText)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(VE.textPrimary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(VE.voiceChip))
                    .padding(.top, 16)
                    .accessibilityAddTraits(.updatesFrequently)

                Button {
                    Task {
                        await viewModel.keepSelectedVoice()
                        if viewModel.didSave { dismiss() }
                    }
                } label: {
                    if viewModel.isSaving {
                        ProgressView().tint(.white)
                    } else {
                        Text("Keep this voice")
                    }
                }
                .buttonStyle(SolidButtonStyle(kind: .blue))
                .disabled(viewModel.isSaving)
                .padding(.top, 12)

                Button("Cancel") { dismiss() }
                    .buttonStyle(SolidButtonStyle(kind: .yellow))
                    .padding(.top, 10)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
        .maroonScreen(title: "")
        .task { await viewModel.loadCurrentSelection() }
        .onDisappear { CloudTTSService.shared.stop() }
    }

    private var card: some View {
        VStack(spacing: 0) {
            Text("Assistant Voice")
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(VE.textPrimary)
                .accessibilityAddTraits(.isHeader)

            Text("Tap a voice to hear it, then choose Keep this voice. It is saved to your account.")
                .font(.system(size: 15))
                .foregroundStyle(VE.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)

            Rectangle()
                .fill(VE.divider)
                .frame(height: 1)
                .padding(.top, 14)
                .padding(.bottom, 6)

            ForEach(AssistantVoice.allCases) { voice in
                voiceButton(voice).padding(.top, 8)
            }
        }
        .padding(20)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.white)
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(Color(hex: 0xF0E5E8), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
        )
    }

    @ViewBuilder
    private func voiceButton(_ voice: AssistantVoice) -> some View {
        let isSelected = viewModel.selectedVoice == voice
        let button = Button {
            viewModel.selectedVoice = voice
            Task { await viewModel.preview(voice) }
        } label: {
            HStack(spacing: 8) {
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                }
                Text(voice.displayName)
            }
        }
        .disabled(viewModel.isPreviewing)
        .accessibilityHint("Plays a sample")
        .accessibilityAddTraits(isSelected ? .isSelected : [])

        if isSelected {
            button.buttonStyle(SolidButtonStyle(kind: .blue, height: 58, fontSize: 16))
        } else {
            button.buttonStyle(MaroonButtonStyle(height: 58, fontSize: 16))
        }
    }

    private var statusText: String {
        if let error = viewModel.errorMessage { return error }
        if viewModel.isSaving { return "Saving..." }
        if viewModel.isPreviewing { return "Playing \(viewModel.selectedVoice.displayName)..." }
        return "Selected: \(viewModel.selectedVoice.displayName)"
    }
}

#Preview {
    NavigationStack { VoiceSettingsView() }
}
