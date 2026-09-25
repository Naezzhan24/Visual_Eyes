import SwiftUI

/// Port of IntroActivity.java + IntroLogoView.java: the VisualED mark is
/// drawn and animated (pop-in, book opens, page-turn flourish, double
/// blink), then "VisualED" pops in letter by letter, then it hands off to
/// login. Tapping anywhere skips. Timings match the Android constants.
struct IntroView: View {
    let onFinished: () -> Void

    @State private var start = Date()
    @State private var didFinish = false

    private static let appName = Array("VisualED")
    private static let accentStart = appName.count - 2 // the "ED" in the wordmark

    var body: some View {
        TimelineView(.animation) { timeline in
            frame(atMs: timeline.date.timeIntervalSince(start) * 1000)
        }
        .contentShape(Rectangle())
        .onTapGesture { finish() }
        .task {
            start = Date()
            try? await Task.sleep(for: .milliseconds(Int(IntroTimeline.totalMs)))
            finish()
        }
    }

    private func frame(atMs ms: Double) -> some View {
        ZStack {
            MaroonBackground()

            VStack(spacing: 14) {
                IntroLogoCanvas(state: IntroTimeline.logoState(atMs: ms))
                    .frame(width: 280, height: 120)
                    .accessibilityHidden(true)

                HStack(alignment: .lastTextBaseline, spacing: 0) {
                    ForEach(Self.appName.indices, id: \.self) { index in
                        letter(index, atMs: ms)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("VisualED")
            }

            VStack {
                Spacer()
                Text("Tap to skip")
                    .font(.system(size: 13))
                    .foregroundStyle(.white)
                    .opacity(IntroTimeline.skipHintAlpha(atMs: ms))
                    .padding(.bottom, 28)
            }
        }
    }

    private func letter(_ index: Int, atMs ms: Double) -> some View {
        let pop = IntroTimeline.letterPop(index: index, atMs: ms)
        let size: CGFloat = index >= Self.accentStart ? 30 * 1.18 : 30
        return Text(String(Self.appName[index]))
            .font(.system(size: size, weight: .bold).width(.condensed))
            .foregroundStyle(.white)
            .opacity(pop.alpha)
            .scaleEffect(pop.scale)
    }

    private func finish() {
        guard !didFinish else { return }
        didFinish = true
        onFinished()
    }
}

// MARK: - Timeline

/// The Android animator chain flattened into one clock.
private enum IntroTimeline {
    static let entranceMs = 420.0
    static let bookOpenMs = 650.0
    static let flipMs = 420.0
    static let blinkDelayMs = 150.0
    static let blinkMs = 950.0

    static let bookOpenStart = entranceMs
    static let flipStart = bookOpenStart + bookOpenMs
    static let blinkStart = flipStart + flipMs + blinkDelayMs
    static let logoEnd = blinkStart + blinkMs

    static let letterStaggerMs = 45.0
    static let letterPopMs = 340.0
    static let letterCount = 8.0
    static let lettersMs = letterCount * letterStaggerMs + letterPopMs
    /// Android: `postDelayed(goToLogin, 650 + lettersDurationMs)` after the logo.
    static let totalMs = logoEnd + 650 + lettersMs

    static func logoState(atMs ms: Double) -> IntroLogoState {
        var state = IntroLogoState()

        let entrance = clamp01(ms / entranceMs)
        state.entranceAlpha = entrance
        state.entranceScale = 0.8 + 0.2 * overshoot(entrance, tension: 1.3)

        let open = decelerate(clamp01((ms - bookOpenStart) / bookOpenMs), factor: 1.4)
        state.bookOpenProgress = ms < bookOpenStart ? 0 : open
        state.pageLineAlpha = clamp01((state.bookOpenProgress - 0.55) / 0.45)

        if ms >= flipStart && ms < flipStart + flipMs {
            // ValueAnimator.ofFloat(1, 0, 1) with accelerate-decelerate.
            let f = accelerateDecelerate((ms - flipStart) / flipMs)
            state.flipScaleX = f < 0.5 ? 1 - f * 2 : (f - 0.5) * 2
        }

        if ms >= blinkStart && ms < logoEnd {
            let f = accelerateDecelerate((ms - blinkStart) / blinkMs)
            state.blinkOpenness = keyframes(f, [(0, 1), (0.24, 0.05), (0.42, 1), (0.58, 1), (0.80, 0.05), (1, 1)])
        }
        return state
    }

    static func letterPop(index: Int, atMs ms: Double) -> (alpha: Double, scale: Double) {
        let begin = logoEnd + Double(index) * letterStaggerMs
        let t = clamp01((ms - begin) / letterPopMs)
        guard ms >= begin else { return (0, 0.3) }
        return (t, 0.3 + 0.7 * overshoot(t, tension: 2.4))
    }

    static func skipHintAlpha(atMs ms: Double) -> Double {
        1 - clamp01((ms - logoEnd - 200) / 200)
    }

    // Android interpolators
    static func overshoot(_ t: Double, tension: Double) -> Double {
        let u = t - 1
        return u * u * ((tension + 1) * u + tension) + 1
    }
    static func decelerate(_ t: Double, factor: Double) -> Double {
        1 - pow(1 - t, 2 * factor)
    }
    static func accelerateDecelerate(_ t: Double) -> Double {
        cos((clamp01(t) + 1) * .pi) / 2 + 0.5
    }
    static func keyframes(_ f: Double, _ frames: [(Double, Double)]) -> Double {
        for i in 1..<frames.count where f <= frames[i].0 {
            let (f0, v0) = frames[i - 1]
            let (f1, v1) = frames[i]
            return v0 + (v1 - v0) * ((f - f0) / (f1 - f0))
        }
        return frames.last?.1 ?? 1
    }
    static func clamp01(_ v: Double) -> Double { max(0, min(1, v)) }
}

struct IntroLogoState {
    var entranceAlpha = 0.0
    var entranceScale = 0.8
    var bookOpenProgress = 0.0
    var pageLineAlpha = 0.0
    var flipScaleX = 1.0
    var blinkOpenness = 1.0
}

// MARK: - Drawing

/// IntroLogoView.onDraw, layer for layer.
private struct IntroLogoCanvas: View {
    let state: IntroLogoState

    // colors.xml intro palette
    private static let redLight = Color(hex: 0xA1121A)
    private static let redDark = Color(hex: 0x4A0508)
    private static let maroonDeep = Color(hex: 0x350002)
    private static let maroonDeepShaded = Color(hex: 0x220001) // maroonDeep blended 35% toward black
    private static let page = Color(hex: 0xF6F6F6)
    private static let pageLine = Color(hex: 0xC9A9AC)

    private static let petalAngles: [Double] = [-52, -26, -2, 20, 42]
    private static let petalLength: [Double] = [0.60, 0.72, 0.64, 0.54, 0.46]
    private static let petalWidth: [Double] = [0.13, 0.16, 0.15, 0.13, 0.11]

    var body: some View {
        Canvas { context, size in
            let g = Geometry(size: size)
            context.opacity = state.entranceAlpha

            let blink = max(0.045, min(1, state.blinkOpenness))
            context.translateBy(x: g.cx, y: g.cy)
            context.scaleBy(x: state.entranceScale, y: state.entranceScale * blink)
            context.translateBy(x: -g.cx, y: -g.cy)

            // Four nested almond layers: outer ribbon, white crease, inner ribbon, deep fill.
            context.fill(g.eye(g.halfW, g.halfH), with: .linearGradient(
                Gradient(colors: [Self.redLight, Self.redDark]),
                startPoint: CGPoint(x: g.cx, y: g.cy - g.halfH),
                endPoint: CGPoint(x: g.cx, y: g.cy + g.halfH * 0.3)))
            context.fill(g.eye(g.halfW * 0.88, g.halfH * 0.86), with: .color(.white))
            context.fill(g.eye(g.halfW * 0.80, g.halfH * 0.76), with: .linearGradient(
                Gradient(colors: [Self.redDark, Self.maroonDeep]),
                startPoint: CGPoint(x: g.cx, y: g.cy - g.halfH * 0.7),
                endPoint: CGPoint(x: g.cx, y: g.cy + g.halfH * 0.7)))
            context.fill(g.eye(g.halfW * 0.66, g.halfH * 0.60), with: .linearGradient(
                Gradient(colors: [Self.maroonDeep, Self.maroonDeepShaded]),
                startPoint: CGPoint(x: g.cx, y: g.cy - g.halfH * 0.55),
                endPoint: CGPoint(x: g.cx, y: g.cy + g.halfH * 0.55)))

            if state.bookOpenProgress > 0.25 {
                let alpha = min(1, (state.bookOpenProgress - 0.25) / 0.55)
                var petals = context
                petals.opacity = alpha
                drawPetals(&petals, g)
            }

            drawBook(&context, g)
        }
    }

    private func drawPetals(_ context: inout GraphicsContext, _ g: Geometry) {
        let baseX = g.cx + g.halfW * 0.58
        let baseY = g.cy - g.halfH * 0.02
        let base = CGPoint(x: baseX, y: baseY)

        for i in Self.petalAngles.indices {
            let rad = Self.petalAngles[i] * .pi / 180
            let dx = cos(rad), dy = sin(rad)
            let px = -dy, py = dx
            let length = g.halfH * Self.petalLength[i]
            let width = g.halfH * Self.petalWidth[i]

            let tip = CGPoint(x: baseX + dx * length, y: baseY + dy * length)
            let ctrlOut = CGPoint(x: baseX + dx * length * 0.62 + px * width * 0.42,
                                  y: baseY + dy * length * 0.62 + py * width * 0.42)
            let ctrlIn = CGPoint(x: baseX + dx * length * 0.62 - px * width * 0.42,
                                 y: baseY + dy * length * 0.62 - py * width * 0.42)
            let capCtrl = CGPoint(x: tip.x + dx * width * 0.2, y: tip.y + dy * width * 0.2)
            let tipL = CGPoint(x: tip.x + px * width / 2, y: tip.y + py * width / 2)
            let tipR = CGPoint(x: tip.x - px * width / 2, y: tip.y - py * width / 2)

            var path = Path()
            path.move(to: base)
            path.addQuadCurve(to: tipL, control: ctrlOut)
            path.addQuadCurve(to: tipR, control: capCtrl)
            path.addQuadCurve(to: base, control: ctrlIn)
            path.closeSubpath()

            context.fill(path, with: .linearGradient(
                Gradient(colors: [Self.maroonDeep, Self.redLight]),
                startPoint: base, endPoint: tip))
        }
    }

    private func drawBook(_ context: inout GraphicsContext, _ g: Geometry) {
        let spineWidth = max(2, g.halfH * 0.05)
        let halfBookW = g.bookHalfWFull * state.bookOpenProgress
        guard halfBookW >= 0.5 else {
            var spine = Path()
            spine.move(to: CGPoint(x: g.cx, y: g.bookTopY))
            spine.addLine(to: CGPoint(x: g.cx, y: g.bookBottomY))
            context.stroke(spine, with: .color(Self.maroonDeep), style: StrokeStyle(lineWidth: spineWidth, lineCap: .round))
            return
        }

        let left = g.page(left: true, halfBookW: halfBookW)
        let right = g.page(left: false, halfBookW: halfBookW)
        let pageStroke = StrokeStyle(lineWidth: max(1.5, g.halfH * 0.03))

        context.fill(left, with: .color(Self.page))
        context.stroke(left, with: .color(Self.redDark), style: pageStroke)
        context.fill(right, with: .color(Self.page))
        context.stroke(right, with: .color(Self.redDark), style: pageStroke)

        if state.pageLineAlpha > 0 {
            let lineStyle = StrokeStyle(lineWidth: max(1.5, g.halfH * 0.032), lineCap: .round)
            for isLeft in [true, false] {
                context.stroke(g.pageLines(left: isLeft, halfBookW: halfBookW),
                               with: .color(Self.pageLine.opacity(state.pageLineAlpha)),
                               style: lineStyle)
            }
        }

        // Page-turn flourish: the right leaf rotates edge-on and darkens.
        var flip = context
        flip.translateBy(x: g.cx, y: g.cy)
        flip.scaleBy(x: state.flipScaleX, y: 1)
        flip.translateBy(x: -g.cx, y: -g.cy)
        let shade = 1 - abs(state.flipScaleX)
        flip.fill(right, with: .color(Self.blendPage(shade)))

        var spine = Path()
        spine.move(to: CGPoint(x: g.cx, y: g.bookTopY + g.bookNotchDepth * 0.9))
        spine.addLine(to: CGPoint(x: g.cx, y: g.bookBottomY - g.bookNotchDepth * 0.9))
        context.stroke(spine, with: .color(Self.maroonDeep), style: StrokeStyle(lineWidth: spineWidth, lineCap: .round))
    }

    /// color_logo_page blended toward color_logo_page_shadow.
    private static func blendPage(_ ratio: Double) -> Color {
        let r = max(0, min(1, ratio))
        let from = (246.0, 246.0, 246.0), to = (156.0, 127.0, 130.0)
        return Color(.sRGB,
                     red: (from.0 + (to.0 - from.0) * r) / 255,
                     green: (from.1 + (to.1 - from.1) * r) / 255,
                     blue: (from.2 + (to.2 - from.2) * r) / 255)
    }

    /// IntroLogoView.onSizeChanged's derived measurements.
    private struct Geometry {
        let cx: CGFloat, cy: CGFloat, halfW: CGFloat, halfH: CGFloat
        let bookHalfWFull: CGFloat, bookTopY: CGFloat, bookBottomY: CGFloat
        let bookNotchDepth: CGFloat, bookOuterBulge: CGFloat

        init(size: CGSize) {
            cx = size.width / 2
            cy = size.height / 2
            let eyeW = size.width * 0.96
            let eyeH = min(eyeW * 0.40, size.height * 0.94)
            halfW = eyeW / 2
            halfH = eyeH / 2
            bookHalfWFull = halfW * 0.34
            bookTopY = cy - halfH * 0.60
            bookBottomY = cy + halfH * 0.60
            bookNotchDepth = (bookBottomY - bookTopY) * 0.34
            bookOuterBulge = bookHalfWFull * 0.10
        }

        func eye(_ hw: CGFloat, _ hh: CGFloat) -> Path {
            let peak = hh * 1.25
            var path = Path()
            path.move(to: CGPoint(x: cx - hw, y: cy))
            path.addCurve(to: CGPoint(x: cx + hw, y: cy),
                          control1: CGPoint(x: cx - hw * 0.5, y: cy - peak),
                          control2: CGPoint(x: cx + hw * 0.5, y: cy - peak))
            path.addCurve(to: CGPoint(x: cx - hw, y: cy),
                          control1: CGPoint(x: cx + hw * 0.5, y: cy + peak),
                          control2: CGPoint(x: cx - hw * 0.5, y: cy + peak))
            path.closeSubpath()
            return path
        }

        func page(left: Bool, halfBookW: CGFloat) -> Path {
            let outerX = left ? cx - halfBookW : cx + halfBookW
            let sign: CGFloat = left ? -1 : 1
            let midY = (bookTopY + bookBottomY) / 2
            var path = Path()
            path.move(to: CGPoint(x: outerX, y: bookTopY))
            path.addQuadCurve(to: CGPoint(x: cx, y: bookTopY + bookNotchDepth), control: CGPoint(x: cx, y: bookTopY))
            path.addLine(to: CGPoint(x: cx, y: bookBottomY - bookNotchDepth))
            path.addQuadCurve(to: CGPoint(x: outerX, y: bookBottomY), control: CGPoint(x: cx, y: bookBottomY))
            path.addQuadCurve(to: CGPoint(x: outerX, y: bookTopY), control: CGPoint(x: outerX + sign * bookOuterBulge, y: midY))
            path.closeSubpath()
            return path
        }

        func pageLines(left: Bool, halfBookW: CGFloat) -> Path {
            let outerX = left ? cx - halfBookW : cx + halfBookW
            let pad = halfBookW * 0.24
            let xOuter = left ? outerX + pad : outerX - pad
            let xSpine = left ? cx - pad * 0.55 : cx + pad * 0.55
            var path = Path()
            for i in 0..<3 {
                let frac = CGFloat(i + 1) / 4
                let y = bookTopY + (bookBottomY - bookTopY) * frac
                let droop = (y - cy) * -0.20
                path.move(to: CGPoint(x: xOuter, y: y))
                path.addQuadCurve(to: CGPoint(x: xSpine, y: y + droop), control: CGPoint(x: (xOuter + xSpine) / 2, y: y))
            }
            return path
        }
    }
}

#Preview {
    IntroView(onFinished: {})
}
