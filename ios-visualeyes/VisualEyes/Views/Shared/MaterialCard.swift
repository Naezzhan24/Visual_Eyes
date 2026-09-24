import SwiftUI

/// Shared card used by Home's "featured material" and the Materials list.
struct MaterialCard: View {
    let material: LearningMaterial

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(material.title)
                .font(.subheadline.bold())
                .lineLimit(2)
            if let date = material.uploadDate {
                Text(date)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    MaterialCard(material: LearningMaterial(id: 1, title: "Introduction to Photosynthesis", filePath: "x.pdf", uploadDate: "2026-09-01"))
        .padding()
}
