import SwiftUI

/// Mirrors `activity_login.xml`: maroon gradient, frosted glass card with the
/// logo on a white plate, maroon-soft inputs, Login + Voice Login buttons,
/// the voice status chip and the Register link.
struct LoginView: View {
    @Environment(SessionStore.self) private var sessionStore
    @State private var viewModel = LoginViewModel()
    @State private var showRegister = false
    @State private var registerByVoice = false
    @State private var isPasswordVisible = false
    @FocusState private var focusedField: Field?

    private enum Field { case schoolId, password }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    card
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 16)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .pinchToZoom()
            .maroonBackground()
            .tripleTapToRepeat { viewModel.repeatLastInstruction() }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showRegister) {
                RegisterView(autoStartVoice: registerByVoice)
            }
        }
        // Greets once; popping back from Register keeps this view alive.
        .onAppear { viewModel.screenAppeared() }
        .onDisappear { viewModel.stopVoice() }
        .onChange(of: viewModel.requestedVoiceRegistration) { _, requested in
            guard requested else { return }
            viewModel.requestedVoiceRegistration = false
            registerByVoice = true
            showRegister = true
        }
    }

    private var card: some View {
        VStack(spacing: 0) {
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 230, height: 121)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(Color.white)
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .stroke(Color(hex: 0xF0DEE3), lineWidth: 1)
                        )
                        .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                )
                .accessibilityLabel("VisualED Logo")

            Text("ACCESSIBLE\nLEARNING SYSTEM")
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.top, 4)
                .accessibilityAddTraits(.isHeader)

            Text("Sign in to continue to your accessible learning system")
                .font(.system(size: 18))
                .foregroundStyle(VE.subtitleOnMaroon)
                .multilineTextAlignment(.center)
                .padding(.top, 2)

            if let message = sessionStore.sessionExpiredMessage {
                Text(message)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xFFE3A3))
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)
                    .onAppear {
                        // Show once, then clear.
                        sessionStore.sessionExpiredMessage = nil
                    }
            }

            MaroonTextField(systemImage: "person.fill", isFocused: focusedField == .schoolId) {
                TextField("", text: $viewModel.schoolId, prompt: maroonPrompt("School ID"))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focusedField, equals: .schoolId)
                    .submitLabel(.next)
                    .onSubmit { focusedField = .password }
                    .accessibilityLabel("School ID")
            }
            .padding(.top, 10)

            MaroonTextField(systemImage: "lock.fill", isFocused: focusedField == .password) {
                Group {
                    if isPasswordVisible {
                        TextField("", text: $viewModel.password, prompt: maroonPrompt("Password (Birthdate MM-DD-YYYY)"))
                    } else {
                        SecureField("", text: $viewModel.password, prompt: maroonPrompt("Password (Birthdate MM-DD-YYYY)"))
                    }
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.password)
                .focused($focusedField, equals: .password)
                .submitLabel(.go)
                .onSubmit { Task { await viewModel.loginTapped() } }
                .accessibilityLabel("Password, your birthdate in month, day, year format")
            } trailing: {
                Button {
                    isPasswordVisible.toggle()
                } label: {
                    Image(systemName: isPasswordVisible ? "eye" : "eye.slash")
                        .font(.system(size: 18))
                        .foregroundStyle(VE.textSecondary)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(isPasswordVisible ? "Hide password" : "Show password")
            }
            .padding(.top, 8)

            if let error = viewModel.errorMessage {
                Text(error)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xFFE3A3))
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)
            }

            Button {
                focusedField = nil
                Task { await viewModel.loginTapped() }
            } label: {
                if viewModel.isLoading {
                    ProgressView().tint(.white)
                } else {
                    Text("Login")
                }
            }
            .buttonStyle(MaroonButtonStyle())
            .padding(.top, 10)

            Button {
                focusedField = nil
                viewModel.voiceLoginTapped()
            } label: {
                Text(viewModel.isVoiceLoginMode ? "Stop Voice Login" : "Voice Login")
            }
            .buttonStyle(SoftMaroonButtonStyle())
            .padding(.top, 10)
            .accessibilityHint("Say your School ID, then your birthdate")

            HStack(spacing: 6) {
                if viewModel.isListening {
                    Image(systemName: "waveform")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(VE.primary)
                        .symbolEffect(.variableColor.iterative)
                        .frame(width: 40, height: 40)
                }
                Text("Voice Status: \(viewModel.voiceStatus)")
                    .font(.system(size: 18, weight: .bold))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(VE.textPrimary)
                    .frame(maxWidth: .infinity)
            }
            .padding(10)
            .frame(minHeight: 48)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(VE.voiceChip))
            .padding(.top, 12)

            Button {
                viewModel.stopVoice()
                registerByVoice = false
                showRegister = true
            } label: {
                Text("Don't have an account? Register")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .frame(minHeight: 48)
            }
            .padding(.top, 10)
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .padding(.bottom, 18)
        .glassCard()
    }
}

#Preview {
    LoginView()
        .environment(SessionStore.shared)
}
