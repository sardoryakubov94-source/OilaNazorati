import SwiftUI

struct StatusView: View {
    let familyCode: String
    let childUid: String
    @AppStorage("location_sharing_consent") private var locationSharingConsent = false

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
                    Toggle(isOn: $locationSharingConsent) {
                        Label("Joylashuvni oilam bilan ulashish", systemImage: "location")
                    }
                    .tint(.blue)
                    .onChange(of: locationSharingConsent) { consent in
                        if consent {
                            LocationSync.shared.start(familyCode: familyCode, childUid: childUid)
                        } else {
                            LocationSync.shared.stop()
                        }
                    }
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
            if locationSharingConsent {
                LocationSync.shared.start(familyCode: familyCode, childUid: childUid)
            }
            if #available(iOS 16.0, *) {
                FocusModeSync.shared.start(familyCode: familyCode, childUid: childUid)
                ScreenTimeController.shared.start(familyCode: familyCode, childUid: childUid)
            }
        }
    }
}
