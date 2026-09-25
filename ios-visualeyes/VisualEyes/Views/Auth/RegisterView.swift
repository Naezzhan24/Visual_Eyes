import SwiftUI

/// Port of `activity_register.xml`: the same glass card as Login, with
/// School ID + Check, then Personal, Academic and Account sections and the
/// maroon Continue button. Voice Registration arrives with the voice-guided
/// flow in a later phase (see `RegisterViewModel`), so it's left out here.
struct RegisterView: View {
    /// Opened by saying "new user" on Login: go straight into voice mode.
    var autoStartVoice = false

    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = RegisterViewModel()
    @FocusState private var focusedField: Field?

    private enum Field { case schoolId, firstName, middleName, lastName, yearLevel, section, email }

    var body: some View {
        ScrollView {
            card
                .padding(20)
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollDismissesKeyboard(.interactively)
        .pinchToZoom()
        .maroonScreen(title: "")
        .tripleTapToRepeat { viewModel.repeatLastInstruction() }
        .onAppear { viewModel.screenAppeared(autoStartVoice: autoStartVoice) }
        .onDisappear { viewModel.screenDisappeared() }
        // Tapping a field to type takes over from the voice flow.
        .onChange(of: focusedField) { _, focused in
            if focused != nil { viewModel.userStartedTyping() }
        }
        .onChange(of: viewModel.requestedReturnToLogin) { _, done in
            if done { dismiss() }
        }
    }

    /// `btnVoiceRegister` + the voice status chip.
    private var voiceControls: some View {
        VStack(spacing: 10) {
            Button {
                focusedField = nil
                viewModel.voiceRegisterTapped()
            } label: {
                Text(viewModel.isVoiceMode ? "Stop Voice Registration" : "Voice Registration")
            }
            .buttonStyle(SoftMaroonButtonStyle(height: 52))

            HStack(spacing: 8) {
                if viewModel.isListening {
                    Image(systemName: "waveform")
                        .font(.system(size: 20, weight: .semibold))
                        .symbolEffect(.variableColor.iterative)
                }
                Text("Voice status: \(viewModel.voiceStatus)")
                    .multilineTextAlignment(.center)
            }
            .font(.system(size: 18, weight: .bold))
            .foregroundStyle(VE.textPrimary)
            .frame(maxWidth: .infinity)
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(VE.voiceChip))
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)
        }
        .padding(.top, 18)
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 0) {
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 230, height: 121)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(Color.white)
                        .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                )
                .frame(maxWidth: .infinity)
                .accessibilityLabel("VisualED Logo")

            Text("Student Registration")
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.top, 10)
                .accessibilityAddTraits(.isHeader)

            Text("Create your account to access the VisualED learning support system")
                .font(.system(size: 18))
                .foregroundStyle(VE.subtitleOnMaroon)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, 4)

            voiceControls

            HStack(spacing: 8) {
                field(.schoolId, "School ID", icon: "person.text.rectangle", text: $viewModel.schoolId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                Button {
                    focusedField = nil
                    Task { await viewModel.checkSchoolIdTapped() }
                } label: {
                    if viewModel.isCheckingSchoolId {
                        ProgressView().tint(VE.primary)
                    } else {
                        Text("Check")
                    }
                }
                .buttonStyle(SoftMaroonButtonStyle(height: 52, fontSize: 18))
                .frame(width: 96)
                .disabled(viewModel.schoolId.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityLabel("Check School ID")
            }
            .padding(.top, 14)

            sectionLabel("Personal Information", top: 22)
            VStack(spacing: 12) {
                field(.firstName, "First Name", icon: "person.fill", text: $viewModel.firstName)
                field(.middleName, "Middle Name (optional)", icon: "person.fill", text: $viewModel.middleName)
                field(.lastName, "Last Name", icon: "person.fill", text: $viewModel.lastName)
            }

            sectionLabel("Academic Information", top: 18)
            VStack(spacing: 12) {
                MaroonTextField(systemImage: "calendar", isFocused: false) {
                    DatePicker(viewModel.birthdateEntered ? "Birthdate" : "Birthdate (tap to set)",
                               selection: $viewModel.birthdate, in: ...Date(), displayedComponents: .date)
                        .foregroundStyle(VE.textSecondary)
                }
                field(.yearLevel, "Year Level (e.g. 1st Year)", icon: "graduationcap.fill", text: $viewModel.yearLevel)
                field(.section, "Section (e.g. BSIT-3A)", icon: "person.3.fill", text: $viewModel.section)
                    .textInputAutocapitalization(.characters)
            }

            sectionLabel("Account Details", top: 18)
            field(.email, "School Email", icon: "envelope.fill", text: $viewModel.email)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)

            if let error = viewModel.errorMessage {
                message(error)
            }
            if let success = viewModel.successMessage {
                message(success)
            }

            if viewModel.successMessage != nil {
                // Stay on screen so the student can read the approval
                // status and their password format before going back.
                Button("Back to Login") { dismiss() }
                    .buttonStyle(MaroonButtonStyle(height: 52))
                    .padding(.top, 18)
            } else {
                Button {
                    focusedField = nil
                    Task { await viewModel.continueTapped() }
                } label: {
                    if viewModel.isSubmitting {
                        ProgressView().tint(.white)
                    } else {
                        Text("Continue")
                    }
                }
                .buttonStyle(MaroonButtonStyle(height: 52))
                .disabled(viewModel.isSubmitting)
                .padding(.top, 18)
            }
        }
        .padding(22)
        .glassCard()
    }

    private func sectionLabel(_ text: String, top: CGFloat) -> some View {
        Text(text)
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(.white)
            .padding(.top, top)
            .padding(.bottom, 8)
            .accessibilityAddTraits(.isHeader)
    }

    private func field(_ field: Field, _ placeholder: String, icon: String, text: Binding<String>) -> some View {
        MaroonTextField(systemImage: icon, isFocused: focusedField == field) {
            TextField("", text: text, prompt: maroonPrompt(placeholder))
                .focused($focusedField, equals: field)
                .accessibilityLabel(placeholder)
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Color(hex: 0xFFE3A3))
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.top, 12)
    }
}

#Preview {
    NavigationStack {
        RegisterView()
    }
}
