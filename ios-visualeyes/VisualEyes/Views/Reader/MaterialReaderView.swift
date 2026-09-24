import SwiftUI

/// Stand-in for AccessibleMaterialActivity.java. Portrait is locked
/// app-wide (see `project.yml`'s `UISupportedInterfaceOrientations`)
/// rather than scoped just to this screen — a deliberate simplification
/// since the whole app targets phone/portrait use, matching the plan's
/// note to confirm this scope before Phase 4.
struct MaterialReaderView: View {
    let material: LearningMaterial
    @State private var viewModel: MaterialReaderViewModel
    @GestureState private var pinchMagnification: CGFloat = 1.0
    @State private var showingFeedback = false

    init(material: LearningMaterial) {
        self.material = material
        _viewModel = State(initialValue: MaterialReaderViewModel(material: material))
    }

    var body: some View {
        VStack(spacing: 16) {
            if viewModel.isLoading {
                ProgressView("Loading material…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load material",
                    systemImage: "exclamationmark.triangle",
                    description: Text(error)
                )
            } else if let chunk = viewModel.currentChunk {
                Text(viewModel.progressText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                ScrollView {
                    Text(highlightedText(for: chunk))
                        .font(.system(size: viewModel.textSizePt, weight: chunk.style == .heading ? .bold : .regular))
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                }
                // Pinch-to-zoom nudges the same text-size control the
                // buttons/slider use, rather than a separate content
                // scale — one consistent "how big is the text" concept,
                // avoiding the gesture-arena conflicts between pinch-zoom
                // and other gestures the Android bug history flagged.
                .gesture(
                    MagnificationGesture()
                        .updating($pinchMagnification) { value, state, _ in
                            state = value
                        }
                        .onEnded { value in
                            let delta = (value - 1.0) * 20
                            viewModel.textSizePt = min(max(viewModel.textSizePt + delta, 14), 34)
                        }
                )

                controls
            } else {
                ContentUnavailableView("No readable text", systemImage: "doc.text")
            }
        }
        .padding(.top)
        .navigationTitle(material.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    viewModel.pause()
                    showingFeedback = true
                } label: {
                    Image(systemName: "bubble.left.and.text.bubble.right")
                }
                .accessibilityLabel("Leave feedback")
            }
        }
        .sheet(isPresented: $showingFeedback) {
            NavigationStack {
                FeedbackView(material: material)
            }
        }
        .task { await viewModel.load() }
        .onDisappear { viewModel.stopEverything() }
    }

    private func highlightedText(for chunk: ReaderChunk) -> AttributedString {
        var attributed = AttributedString(chunk.text)
        if let nsRange = viewModel.highlightedRange,
           let stringRange = Range(nsRange, in: chunk.text),
           let attributedRange = Range(stringRange, in: attributed) {
            attributed[attributedRange].backgroundColor = .yellow.opacity(0.6)
        } else if viewModel.isPlaying {
            // Paragraph-wide wash fallback — shown until the first real
            // word-boundary event arrives for this chunk. Not every
            // voice/locale is guaranteed to fire one, even natively on
            // iOS, so this fallback isn't just an Android quirk to shed.
            attributed.backgroundColor = .yellow.opacity(0.25)
        }
        return attributed
    }

    private var controls: some View {
        VStack(spacing: 12) {
            HStack(spacing: 24) {
                Button { viewModel.previous() } label: {
                    Image(systemName: "backward.end.fill")
                }
                .disabled(viewModel.isAtFirstChunk)
                .accessibilityLabel("Previous paragraph")

                Button { viewModel.togglePlayback() } label: {
                    Image(systemName: viewModel.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 44))
                }
                .accessibilityLabel(viewModel.isPlaying ? "Pause reading" : "Start reading")

                Button { viewModel.next() } label: {
                    Image(systemName: "forward.end.fill")
                }
                .disabled(viewModel.isAtLastChunk)
                .accessibilityLabel("Next paragraph")
            }

            HStack {
                Button { viewModel.decreaseTextSize() } label: {
                    Image(systemName: "textformat.size.smaller")
                }
                Slider(value: $viewModel.textSizePt, in: 14...34, step: 2)
                Button { viewModel.increaseTextSize() } label: {
                    Image(systemName: "textformat.size.larger")
                }
            }
            .padding(.horizontal)
            .accessibilityLabel("Text size")

            HStack(spacing: 20) {
                Button("Slower") { viewModel.slower() }
                Button("Repeat") { viewModel.repeatChunk() }
                Button("Faster") { viewModel.faster() }
            }
            .font(.footnote)
        }
        .padding(.bottom)
    }
}
