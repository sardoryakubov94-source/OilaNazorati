import SwiftUI

struct StatusView: View {
    let familyCode: String
    let childUid: String

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "checkmark.shield.fill")
                .font(.system(size: 64))
                .foregroundColor(.green)
            Text("Ulangan").font(.title).bold()
            Text("Oila kodi: \(familyCode)")
                .font(.footnote)
                .foregroundColor(.secondary)
            Text("Joylashuv kuzatuvi va Kontrol rejim faol. Ota-ona Android ilovasidan boshqaradi.")
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal, 32)
            Spacer()
        }
        .padding(.top, 80)
        .onAppear {
            LocationSync.shared.start(familyCode: familyCode, childUid: childUid)
            if #available(iOS 16.0, *) {
                FocusModeSync.shared.start(familyCode: familyCode, childUid: childUid)
            }
        }
    }
}
