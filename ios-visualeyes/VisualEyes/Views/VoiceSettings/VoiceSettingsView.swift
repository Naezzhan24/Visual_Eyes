import SwiftUI

/// Stand-in for VoiceSettingsActivity.java.
struct VoiceSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = VoiceSettingsViewModel()

    var body: some View {
        List {
            Section("Choose an assistant voice") {
                ForEach(AssistantVoice.allCases) { voice in
                    HStack {
                        Text(voice.displayName)
                        Spacer()
                        Button {
                            Task { await viewModel.preview(voice) }
                        } label: {
                            Image(systemName: "play.circle")
                        }
                        .disabled(viewModel.isPreviewing)
                        .accessibilityLabel("Preview \(voice.displayName)")

                        if viewModel.selectedVoice == voice {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.accentColor)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { viewModel.selectedVoice = voice }
                }
            }

            if let error = viewModel.errorMessage {
                Section {
                    Text(error).foregroundStyle(.red)
                }
            }

            Section {
                Button {
                    Task {
                        await viewModel.keepSelectedVoice()
                        if viewModel.didSave { dismiss() }
                    }
                } label: {
                    if viewModel.isSaving {
                        ProgressView()
                    } else {
                        Text("Keep This Voice")
                    }
                }
            }
        }
        .navigationTitle("Assistant Voice")
        .task { await viewModel.loadCurrentSelection() }
    }
}

#Preview {
    NavigationStack { VoiceSettingsView() }
}
