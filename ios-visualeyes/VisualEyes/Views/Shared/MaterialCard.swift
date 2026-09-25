import SwiftUI

/// One row of the Recent Learning Materials list — MaterialsFragment's
/// `createMaterialRow`: 72pt tall, white icon, title, arrow.
struct MaterialCard: View {
    let material: LearningMaterial

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.text.fill")
                .font(.system(size: 20))
                .frame(width: 24, height: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(material.title)
                    .font(.system(size: 16))
                    .lineLimit(2)
                if let date = material.uploadDate {
                    Text(date)
                        .font(.system(size: 13))
                        .foregroundStyle(VE.subtitleOnMaroon)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "play.fill")
                .font(.system(size: 14))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .frame(minHeight: 72)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    MaterialCard(material: LearningMaterial(id: 1, title: "Introduction to Photosynthesis", filePath: "x.pdf", uploadDate: "2026-09-01"))
        .translucentCard()
        .padding()
        .maroonBackground()
}
