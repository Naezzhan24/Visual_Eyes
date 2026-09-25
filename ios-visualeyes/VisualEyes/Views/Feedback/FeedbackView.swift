import SwiftUI

/// FeedbackActivity.java's 5-step wizard, each step drawn as the matching
/// translucent card from `activity_feedback.xml`.
struct FeedbackView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: FeedbackViewModel

    init(material: LearningMaterial) {
        _viewModel = State(initialValue: FeedbackViewModel(material: material))
    }

    var body: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 0) {
                Text("Feedback")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(.white)
                    .accessibilityAddTraits(.isHeader)
                Text("We value your voice")
                    .font(.system(size: 18))
                    .foregroundStyle(VE.subtitleOnMaroon)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if viewModel.didSubmit {
                thankYou
            } else {
                voiceControls

                ProgressView(value: Double(viewModel.step.rawValue + 1), total: Double(FeedbackStep.allCases.count))
                    .tint(.white)
                    .accessibilityLabel("Step \(viewModel.step.rawValue + 1) of \(FeedbackStep.allCases.count)")

                ScrollView {
                    stepContent
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(20)
                        .translucentCard()
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.interactively)

                if let error = viewModel.errorMessage {
                    Text(error)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color(hex: 0xFFE3A3))
                        .multilineTextAlignment(.center)
                }

                HStack(spacing: 12) {
                    if viewModel.step != .rating {
                        Button("Back") { viewModel.goToPreviousStep() }
                            .buttonStyle(SoftMaroonButtonStyle(height: 56))
                    }
                    Button {
                        viewModel.goToNextStep()
                    } label: {
                        if viewModel.isSubmitting {
                            ProgressView().tint(.white)
                        } else {
                            Text(viewModel.isLastStep ? "Submit Feedback" : "Next")
                        }
                    }
                    .buttonStyle(MaroonButtonStyle(height: 56))
                    .disabled(!viewModel.canProceed || viewModel.isSubmitting)
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.bottom, 18)
        .maroonScreen(title: "")
        .onAppear { viewModel.screenAppeared() }
        .onDisappear { viewModel.screenDisappeared() }
        .onChange(of: viewModel.requestedClose) { _, close in
            if close { dismiss() }
        }
    }

    /// `btnVoiceFeedback` + `txtVoiceStatus`.
    private var voiceControls: some View {
        VStack(spacing: 10) {
            Button {
                if viewModel.isVoiceMode {
                    viewModel.stopVoiceFlow()
                } else {
                    viewModel.startVoiceFeedbackFlow()
                }
            } label: {
                Label(viewModel.isVoiceMode ? "Stop Voice Feedback" : "Voice Feedback",
                      systemImage: viewModel.isVoiceMode ? "stop.fill" : "mic.fill")
            }
            .buttonStyle(SoftMaroonButtonStyle(height: 52))

            HStack(spacing: 8) {
                if viewModel.isListening {
                    Image(systemName: "waveform")
                        .font(.system(size: 18, weight: .semibold))
                        .symbolEffect(.variableColor.iterative)
                }
                Text(viewModel.voiceStatus)
                    .multilineTextAlignment(.center)
            }
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(VE.textPrimary)
            .frame(maxWidth: .infinity)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(VE.voiceChip))
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)
        }
    }

    /// `btnVoiceMaterial` / `btnVoiceInstructor`: dictate into this box.
    private var useVoiceButton: some View {
        Button {
            viewModel.dictateIntoCurrentField()
        } label: {
            Label("Use Voice", systemImage: "mic.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(VE.primary)
                .padding(.horizontal, 18)
                .frame(height: 46)
                .background(Capsule().fill(Color.white))
                .overlay(Capsule().stroke(VE.primary, lineWidth: 1.5))
        }
        .accessibilityHint("Speak and it will be added to this feedback")
    }

    private var thankYou: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 56))
                .foregroundStyle(.white)
            Text("Thank you!")
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(.white)
            Text("Your feedback was submitted.")
                .font(.system(size: 18))
                .foregroundStyle(VE.subtitleOnMaroon)
            Spacer()
            Button("Done") { dismiss() }
                .buttonStyle(MaroonButtonStyle(height: 56))
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var stepContent: some View {
        switch viewModel.step {
        case .rating:
            VStack(spacing: 12) {
                Image(systemName: "star.fill")
                    .font(.system(size: 36))
                    .foregroundStyle(Color(hex: 0xE0A93A))
                    .accessibilityHidden(true)
                cardTitle("Rate your satisfaction")
                HStack(spacing: 8) {
                    ForEach(1...5, id: \.self) { star in
                        Button {
                            viewModel.rating = star
                        } label: {
                            Image(systemName: star <= viewModel.rating ? "star.fill" : "star")
                                .font(.system(size: 34))
                                .foregroundStyle(Color(hex: 0xE0A93A))
                                .frame(width: 48, height: 48)
                        }
                        .accessibilityLabel("\(star) star\(star == 1 ? "" : "s")")
                        .accessibilityAddTraits(star == viewModel.rating ? .isSelected : [])
                    }
                }
                Text(viewModel.rating > 0
                     ? "\(viewModel.rating) Star\(viewModel.rating == 1 ? "" : "s") - \(FeedbackPreferences.satisfactionLabel(forRating: viewModel.rating))"
                     : "Please select a rating")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(VE.subtitleOnMaroon)
            }
            .frame(maxWidth: .infinity)

        case .textSize:
            VStack(alignment: .leading, spacing: 10) {
                cardTitle("How is the size of the text?")
                cardSubtitle("Pick an option and you will see the change right away. Keep adjusting until it looks just right.")
                Text("This is how your text will look.")
                    .font(.system(size: viewModel.workTextSize))
                    .foregroundStyle(VE.textBody)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(inputBackground(focused: false))
                ForEach(Array(FeedbackPreferences.sizeLabels.enumerated()), id: \.offset) { index, label in
                    optionButton(label, isSelected: viewModel.textSizeLevel == index + 1) {
                        viewModel.selectTextSizeLevel(index + 1)
                    }
                }
                cardStatus(viewModel.textSizePreviewText)
            }

        case .speed:
            VStack(alignment: .leading, spacing: 10) {
                cardTitle("How is the reading speed of the voice?")
                cardSubtitle("Pick an option and you will hear the change right away. Keep adjusting until it sounds just right.")
                ForEach(Array(FeedbackPreferences.speedLabels.enumerated()), id: \.offset) { index, label in
                    optionButton(label, isSelected: viewModel.speedLevel == index + 1) {
                        viewModel.selectSpeedLevel(index + 1)
                    }
                }
                cardStatus(viewModel.speedPreviewText)
            }

        case .materialFeedback:
            VStack(alignment: .leading, spacing: 12) {
                cardTitle("Material Feedback")
                useVoiceButton
                feedbackEditor($viewModel.materialFeedbackText,
                               placeholder: "Enter your feedback about the learning material...")
            }

        case .instructorFeedback:
            VStack(alignment: .leading, spacing: 12) {
                cardTitle("Instructor Feedback")
                useVoiceButton
                feedbackEditor($viewModel.instructorFeedbackText,
                               placeholder: "Enter your feedback for the instructor...")
            }
        }
    }

    private func cardTitle(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 18, weight: .bold))
            .foregroundStyle(.white)
            .accessibilityAddTraits(.isHeader)
    }

    private func cardSubtitle(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 16))
            .foregroundStyle(VE.subtitleOnMaroon)
    }

    private func cardStatus(_ text: String) -> some View {
        Text(text.isEmpty ? "Not answered" : text)
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(VE.subtitleOnMaroon)
    }

    @ViewBuilder
    private func optionButton(_ label: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        let button = Button(label, action: action)
            .accessibilityAddTraits(isSelected ? .isSelected : [])
        if isSelected {
            button.buttonStyle(MaroonButtonStyle(height: 48, fontSize: 16))
        } else {
            button.buttonStyle(SoftMaroonButtonStyle(height: 48, fontSize: 16))
        }
    }

    /// `bg_input_field`: white, 16pt corners, maroon ring when focused.
    private func inputBackground(focused: Bool) -> some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(Color.white)
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(focused ? VE.primary : VE.divider, lineWidth: focused ? 2 : 1)
            )
    }

    private func feedbackEditor(_ text: Binding<String>, placeholder: String) -> some View {
        TextEditor(text: text)
            .font(.system(size: 18))
            .foregroundStyle(VE.textBody)
            .tint(VE.primary)
            .scrollContentBackground(.hidden)
            .padding(12)
            .frame(minHeight: 140)
            .background(inputBackground(focused: false))
            .overlay(alignment: .topLeading) {
                if text.wrappedValue.isEmpty {
                    Text(placeholder)
                        .font(.system(size: 18))
                        .foregroundStyle(Color(hex: 0x9E9E9E))
                        .padding(.horizontal, 17)
                        .padding(.vertical, 20)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityLabel(placeholder)
    }
}

#Preview {
    NavigationStack {
        FeedbackView(material: LearningMaterial(id: 1, title: "Sample", filePath: "x.pdf", uploadDate: nil))
    }
}
