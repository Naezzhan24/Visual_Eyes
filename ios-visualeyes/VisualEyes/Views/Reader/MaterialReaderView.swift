import SwiftUI

/// Port of AccessibleMaterialActivity.java / `activity_accessible_material.xml`:
/// a translucent controls card (title, progress, text size, playback) above
/// the white reading card, on the maroon gradient.
///
/// Portrait is locked app-wide (see `project.yml`'s
/// `UISupportedInterfaceOrientations`) rather than scoped just to this
/// screen — a deliberate simplification since the whole app targets
/// phone/portrait use, matching the plan's note to confirm this scope
/// before Phase 4.
struct MaterialReaderView: View {
    let material: LearningMaterial
    @State private var viewModel: MaterialReaderViewModel
    @GestureState private var pinchMagnification: CGFloat = 1.0
    @State private var showingFeedback = false
    @Environment(\.dismiss) private var dismiss

    init(material: LearningMaterial) {
        self.material = material
        _viewModel = State(initialValue: MaterialReaderViewModel(material: material))
    }

    var body: some View {
        VStack(spacing: 10) {
            voiceStatusChip

            if viewModel.isLoading {
                ProgressView("Loading material…")
                    .tint(.white)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load material",
                    systemImage: "exclamationmark.triangle",
                    description: Text(error)
                )
                .foregroundStyle(.white)
            } else if let chunk = viewModel.currentChunk {
                controls

                ScrollView {
                    Text(highlightedText(for: chunk))
                        .font(.system(size: viewModel.textSizePt, weight: chunk.style == .heading ? .bold : .regular))
                        .foregroundStyle(VE.textBody)
                        .lineSpacing(10)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(22)
                }
                .scrollIndicators(.hidden)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .shadow(color: .black.opacity(0.2), radius: 5, y: 3)
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
            } else {
                ContentUnavailableView("No readable text", systemImage: "doc.text")
                    .foregroundStyle(.white)
            }
        }
        .padding(.horizontal, 18)
        .padding(.bottom, 18)
        .maroonScreen(title: "")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    viewModel.openFeedback()
                } label: {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 15))
                        .foregroundStyle(.white)
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(VE.primary))
                }
                .accessibilityLabel("Leave feedback")
            }
        }
        .sheet(isPresented: $showingFeedback) {
            NavigationStack {
                FeedbackView(material: material)
            }
        }
        .onAppear { viewModel.screenAppeared() }
        .onDisappear { viewModel.screenDisappeared() }
        // Voice "feedback" / "back".
        .onChange(of: viewModel.requestedFeedback) { _, requested in
            if requested {
                viewModel.requestedFeedback = false
                showingFeedback = true
            }
        }
        .onChange(of: viewModel.requestedBack) { _, requested in
            if requested { dismiss() }
        }
        // Feedback is a sheet, so the reader never "disappears" under it:
        // go quiet while it's up and pick up again when it closes.
        .onChange(of: showingFeedback) { _, showing in
            if showing { viewModel.screenDisappeared() } else { viewModel.screenAppeared() }
        }
    }

    /// `txtVoiceStatus`: what the reader is doing or heard.
    private var voiceStatusChip: some View {
        HStack(spacing: 8) {
            if viewModel.isListening {
                Image(systemName: "waveform")
                    .font(.system(size: 15, weight: .semibold))
                    .symbolEffect(.variableColor.iterative)
            }
            Text(viewModel.voiceStatus)
                .lineLimit(2)
        }
        .font(.system(size: 14, weight: .bold))
        .foregroundStyle(VE.textPrimary)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(VE.voiceChip))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private var isReadingNow: Bool { viewModel.isReading || viewModel.isPlaying }

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

    /// `cardControls`: title, progress, text size and playback controls.
    private var controls: some View {
        VStack(spacing: 8) {
            Text(material.title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .accessibilityAddTraits(.isHeader)

            Text(viewModel.progressText)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(.white)

            Text("Adjust Text Size")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(.white)
                .padding(.top, 4)

            HStack(spacing: 8) {
                Button("A-") { viewModel.decreaseTextSize() }
                    .buttonStyle(SquaredMaroonButtonStyle())
                    .frame(width: 52)
                    .accessibilityLabel("Decrease text size")
                Slider(value: $viewModel.textSizePt, in: 14...34, step: 2)
                    .tint(.white)
                    .accessibilityLabel("Text size")
                Button("A+") { viewModel.increaseTextSize() }
                    .buttonStyle(SquaredMaroonButtonStyle())
                    .frame(width: 52)
                    .accessibilityLabel("Increase text size")
            }

            Text("Text Size: \(Int(viewModel.textSizePt))pt")
                .font(.system(size: 12))
                .foregroundStyle(VE.subtitleOnMaroon)

            HStack(spacing: 12) {
                Button { viewModel.previous() } label: {
                    Image(systemName: "backward.end.fill")
                }
                .buttonStyle(SquaredMaroonButtonStyle())
                .frame(width: 52)
                .disabled(viewModel.isAtFirstChunk)
                .accessibilityLabel("Previous paragraph")

                Button { viewModel.togglePlayback() } label: {
                    Label(isReadingNow ? "Pause" : "Continue",
                          systemImage: isReadingNow ? "pause.fill" : "play.fill")
                }
                .buttonStyle(SquaredMaroonButtonStyle())
                .accessibilityLabel(isReadingNow ? "Pause reading" : "Start reading")

                Button { viewModel.next() } label: {
                    Image(systemName: "forward.end.fill")
                }
                .buttonStyle(SquaredMaroonButtonStyle())
                .frame(width: 52)
                .disabled(viewModel.isAtLastChunk)
                .accessibilityLabel("Next paragraph")
            }
            .padding(.top, 4)

            HStack(spacing: 12) {
                Button("Slower") { viewModel.slower() }
                Button("Repeat") { viewModel.repeatChunk() }
                Button("Faster") { viewModel.faster() }
            }
            .buttonStyle(SquaredMaroonButtonStyle(height: 40, fontSize: 13))

            Text("Say yes to start reading • Say no to stop • Say increase text or decrease text to adjust size")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(VE.subtitleOnMaroon)
                .multilineTextAlignment(.center)
                .padding(.top, 4)
        }
        .padding(14)
        .translucentCard(cornerRadius: 20)
    }
}

/// `bg_button_ripple_squared`: solid primary, 14pt corners, white label.
private struct SquaredMaroonButtonStyle: ButtonStyle {
    var height: CGFloat = 48
    var fontSize: CGFloat = 15

    func makeBody(configuration: Configuration) -> some View {
        SquaredBody(configuration: configuration, height: height, fontSize: fontSize)
    }

    private struct SquaredBody: View {
        let configuration: Configuration
        let height: CGFloat
        let fontSize: CGFloat
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            configuration.label
                .font(.system(size: fontSize, weight: .bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(VE.primary))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.white.opacity(configuration.isPressed ? 0.33 : 0))
                )
                .opacity(isEnabled ? 1 : 0.5)
        }
    }
}
