import Foundation
import CoreGraphics

/// Pure "try it first" text-size/reading-speed preference logic, ported
/// verbatim from `FeedbackActivity.java`'s `applyTextSizeLevel`/
/// `applySpeedLevel`/`targetTextSize`/`targetSpeedScale`/
/// `describeTextSizeAnswer`/`describeSpeedAnswer`/the feedback blob
/// template. Kept framework-free and separately unit-tested since the
/// exact wording here is what instructors/admins read on the backend
/// side — drift here isn't just cosmetic.
enum FeedbackPreferences {
    static let sizeLabels = ["Much too small", "A little small", "Just right", "A little big", "Much too big"]
    static let speedLabels = ["Much too slow", "A little slow", "Just right", "A little fast", "Much too fast"]
    private static let sizeDeltaSp: [CGFloat] = [4, 2, 0, -2, -4]
    private static let speedDelta: [Double] = [0.10, 0.05, 0, -0.05, -0.10]
    static let minTextSize: CGFloat = 14
    static let maxTextSize: CGFloat = 42
    static let minSpeedScale = 0.70
    static let maxSpeedScale = 1.30

    /// One step on the text-size scale, taken from `current` (not the
    /// original base) so repeated taps keep nudging until it feels
    /// right. Level 3 ("Just right") is a no-op.
    static func targetTextSize(current: CGFloat, level: Int) -> CGFloat {
        guard (1...5).contains(level), level != 3 else { return current }
        return min(maxTextSize, max(minTextSize, current + sizeDeltaSp[level - 1]))
    }

    static func targetSpeedScale(current: Double, level: Int) -> Double {
        guard (1...5).contains(level), level != 3 else { return current }
        let target = ((current + speedDelta[level - 1]) * 100).rounded() / 100
        return min(maxSpeedScale, max(minSpeedScale, target))
    }

    static func percent(_ scale: Double) -> Int {
        Int((scale * 100).rounded())
    }

    /// `v == round(v) ? "\(round(v))" : "%.1f"` — matches Java's `fmt()`.
    static func formatted(_ value: CGFloat) -> String {
        let rounded = value.rounded()
        return value == rounded ? String(Int(rounded)) : String(format: "%.1f", value)
    }

    /// Verbatim from `describeTextSizeAnswer()`.
    static func describeTextSize(level: Int, base: CGFloat, work: CGFloat, adjustments: Int) -> String {
        guard level != 0 else { return "Not answered" }
        if work == base {
            return "\(sizeLabels[level - 1]) (no change, \(formatted(base))sp)"
        }
        let stepWord = adjustments == 1 ? " step: " : " steps: "
        return "Adjusted after previewing \(adjustments)\(stepWord)\(formatted(base))sp -> \(formatted(work))sp (last answer: \(sizeLabels[level - 1]))"
    }

    /// Verbatim from `describeSpeedAnswer()`.
    static func describeSpeed(level: Int, base: Double, work: Double, adjustments: Int) -> String {
        guard level != 0 else { return "Not answered" }
        if work == base {
            return "\(speedLabels[level - 1]) (no change, \(percent(base))%)"
        }
        let stepWord = adjustments == 1 ? " step: " : " steps: "
        return "Adjusted after previewing \(adjustments)\(stepWord)\(percent(base))% -> \(percent(work))% (last answer: \(speedLabels[level - 1]))"
    }

    static func satisfactionLabel(forRating rating: Int) -> String {
        switch rating {
        case 1: return "Not Satisfied"
        case 2: return "Slightly Satisfied"
        case 3: return "Neutral"
        case 4: return "Satisfied"
        default: return "Very Satisfied"
        }
    }

    /// Verbatim blob template from `FeedbackActivity.java`'s
    /// `student_submit_feedback` call site — must stay byte-for-byte
    /// identical, since nothing server-side parses per-field; anything
    /// downstream that reads this blob (instructor dashboard, admin
    /// site) expects exactly this shape, `\n`-joined, no trailing
    /// newline.
    static func feedbackBlob(
        rating: Int,
        textSizeAnswer: String,
        speedAnswer: String,
        materialFeedback: String,
        instructorFeedback: String
    ) -> String {
        let material = materialFeedback.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "No material feedback provided."
            : materialFeedback
        let instructor = instructorFeedback.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "No instructor feedback provided."
            : instructorFeedback

        return "Satisfaction: \(satisfactionLabel(forRating: rating))"
            + "\nText Size: \(textSizeAnswer)"
            + "\nReading Speed: \(speedAnswer)"
            + "\nMaterial Feedback: \(material)"
            + "\nInstructor Feedback: \(instructor)"
    }
}
