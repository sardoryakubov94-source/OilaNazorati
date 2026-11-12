import SwiftUI

struct StatusView: View {
    let familyCode: String
    let childUid: String

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        Image(systemName: "checkmark.shield.fill")
                            .font(.system(size: 38))
                            .foregroundStyle(.green)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Qurilma oilaga ulangan").font(.headline)
                            Text("Oila kodi: \(familyCode)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 8)
                }
                Section("Nazorat imkoniyatlari") {
                    Label("Rozilik asosida joylashuv", systemImage: "location")
                    if #available(iOS 16.0, *) {
                        NavigationLink {
                            ScreenTimeChildDashboardView(familyCode: familyCode, childUid: childUid)
                        } label: {
                            Label("Ekran vaqti va ilovalar", systemImage: "hourglass")
                        }
                        Label("Kunlik va haftalik hisobotlar", systemImage: "chart.bar")
                        Label("Maktab va uyqu jadvali", systemImage: "moon.zzz")
                        Label("Ilova va sayt cheklovlari", systemImage: "hand.raised")
                    } else {
                        Label("Screen Time uchun iOS 16 yoki yangiroq versiya kerak", systemImage: "info.circle")
                            .foregroundStyle(.secondary)
                    }
                }
                Section {
                    Text("Apple Screen Time funksiyalari bolaning qurilmasidagi Family Controls ruxsati va ilova tanlovidan keyin ishlaydi. Android'dagi SMS, qo‘ng‘iroq tarixi, bildirishnoma matni, ekran surati va fon mikrofoni iOS’da mavjud emas.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Bola qurilmasi")
        }
        .onAppear {
            LocationSync.shared.start(familyCode: familyCode, childUid: childUid)
            if #available(iOS 16.0, *) {
                FocusModeSync.shared.start(familyCode: familyCode, childUid: childUid)
                ScreenTimeController.shared.start(familyCode: familyCode, childUid: childUid)
            }
        }
    }
}
