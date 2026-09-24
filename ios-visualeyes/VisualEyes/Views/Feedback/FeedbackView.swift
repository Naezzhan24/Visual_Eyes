import SwiftUI

/// Stand-in for FeedbackActivity.java's 5-step wizard.
struct FeedbackView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: FeedbackViewModel

    init(material: LearningMaterial) {
        _viewModel = State(initialValue: FeedbackViewModel(material: material))
    }

    var body: some View {
        VStack(spacing: 20) {
            if viewModel.didSubmit {
                ContentUnavailableView(
                    "Thank you!",
                    systemImage: "checkmark.circle.fill",
                    description: Text("Your feedback was submitted.")
                )
                Button("Done") { dismiss() }
                    .buttonStyle(.borderedProminent)
            } else {
                ProgressView(value: Double(viewModel.step.rawValue + 1), total: Double(FeedbackStep.allCases.count))
                    .padding(.horizontal)

                ScrollView {
                    stepContent
                        .padding()
                }

                if let error = viewModel.errorMessage {
                    Text(error).foregroundStyle(.red).font(.footnote).padding(.horizontal)
                }

                HStack {
                    if viewModel.step != .rating {
                        Button("Back") { viewModel.goToPreviousStep() }
                    }
                    Spacer()
                    Button(viewModel.isLastStep ? "Submit" : "Next") {
                        viewModel.goToNextStep()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!viewModel.canProceed || viewModel.isSubmitting)
                }
                .padding(.horizontal)
                .padding(.bottom)
            }
        }
        .navigationTitle("Feedback")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var stepContent: some View {
        switch viewModel.step {
        case .rating:
            VStack(spacing: 12) {
                Text("How was this material overall?").font(.headline)
                HStack(spacing: 8) {
                    ForEach(1...5, id: \.self) { star in
                        Image(systemName: star <= viewModel.rating ? "star.fill" : "star")
                            .font(.title)
                            .foregroundStyle(.yellow)
                            .onTapGesture { viewModel.rating = star }
                    }
                }
                if viewModel.rating > 0 {
                    Text("\(viewModel.rating) Star\(viewModel.rating == 1 ? "" : "s") - \(FeedbackPreferences.satisfactionLabel(forRating: viewModel.rating))")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

        case .textSize:
            VStack(spacing: 12) {
                Text("How was the text size?").font(.headline)
                Text("Sample text").font(.system(size: viewModel.workTextSize))
                ForEach(Array(FeedbackPreferences.sizeLabels.enumerated()), id: \.offset) { index, label in
                    Button(label) { viewModel.selectTextSizeLevel(index + 1) }
                        .buttonStyle(.bordered)
                        .tint(viewModel.textSizeLevel == index + 1 ? .accentColor : .gray)
                }
                if !viewModel.textSizePreviewText.isEmpty {
                    Text(viewModel.textSizePreviewText).font(.footnote).foregroundStyle(.secondary)
                }
            }

        case .speed:
            VStack(spacing: 12) {
                Text("How was the reading speed?").font(.headline)
                ForEach(Array(FeedbackPreferences.speedLabels.enumerated()), id: \.offset) { index, label in
                    Button(label) { viewModel.selectSpeedLevel(index + 1) }
                        .buttonStyle(.bordered)
                        .tint(viewModel.speedLevel == index + 1 ? .accentColor : .gray)
                }
                if !viewModel.speedPreviewText.isEmpty {
                    Text(viewModel.speedPreviewText).font(.footnote).foregroundStyle(.secondary)
                }
            }

        case .materialFeedback:
            VStack(alignment: .leading, spacing: 8) {
                Text("Anything to add about this material?").font(.headline)
                TextEditor(text: $viewModel.materialFeedbackText)
                    .frame(minHeight: 120)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(.quaternary))
            }

        case .instructorFeedback:
            VStack(alignment: .leading, spacing: 8) {
                Text("Anything for your instructor?").font(.headline)
                TextEditor(text: $viewModel.instructorFeedbackText)
                    .frame(minHeight: 120)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(.quaternary))
            }
        }
    }
}

#Preview {
    NavigationStack {
        FeedbackView(material: LearningMaterial(id: 1, title: "Sample", filePath: "x.pdf", uploadDate: nil))
    }
}
