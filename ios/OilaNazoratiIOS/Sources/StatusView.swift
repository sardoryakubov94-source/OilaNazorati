import SwiftUI

struct StatusView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var locationSync = LocationSync.shared
    let familyCode: String
    let childUid: String
    @AppStorage("location_sharing_consent") private var locationSharingConsent = false
    @StateObject private var audioConsent = ChildAudioConsent()
    @StateObject private var audioRemote = ChildAudioRemoteController()

    private var locationPermissionText: String {
        switch locationSync.authorizationStatus {
        case .notDetermined: return "So‘ralmagan"
        case .restricted: return "Cheklangan"
        case .denied: return "Rad etilgan"
        case .authorizedWhenInUse: return "Ilova ishlaganda"
        case .authorizedAlways: return "Doimiy ruxsat"
        @unknown default: return "Noma’lum"
        }
    }

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
                    HStack {
                        Text("iOS ruxsati")
                        Spacer()
                        Text(locationPermissionText)
                            .font(.caption).foregroundStyle(.secondary)
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
                Section("Jonli ovoz") {
                    Label("Mikrofon: \(audioConsent.permissionText)", systemImage: audioConsent.hasSystemPermission ? "mic.fill" : "mic.slash")
                        .font(.footnote)
                        .foregroundStyle(audioConsent.hasSystemPermission ? .green : .secondary)
                    Toggle("Jonli ovoz funksiyasiga rozilik", isOn: Binding(
                        get: { audioConsent.isEnabled },
                        set: { enabled in
                            if enabled {
                                audioConsent.enable { granted in
                                    if granted {
                                        audioRemote.startListening(familyCode: familyCode, childUid: childUid, consent: audioConsent)
                                    } else {
                                        audioConsent.disable()
                                        audioRemote.stopListening()
                                    }
                                }
                            } else {
                                audioConsent.disable()
                                audioRemote.stopPublishing()
                            }
                        }
                    ))
                    .tint(.blue)
                    Label(audioRemote.statusText, systemImage: audioRemote.isPublishing ? "waveform" : "mic.badge.xmark")
                        .font(.footnote)
                        .foregroundStyle(audioRemote.isPublishing ? .green : .secondary)
                    if audioRemote.isPublishing {
                        Button(role: .destructive) {
                            audioRemote.stopPublishing()
                        } label: {
                            Label("Hozirgi ovoz uzatishni to‘xtatish", systemImage: "stop.circle")
                        }
                        .font(.footnote)
                    }
                    Text("Rozilik bir marta yoqiladi. Keyin ota-ona so‘rov yuborganda, ilova ochiq va faol bo‘lsa, ovoz avtomatik uzatiladi — boladan har safar alohida tasdiq so‘ralmaydi. Mikrofon ishlayotganida iOS indikatori ko‘rinadi. Ilova faol bo‘lmaganida uzatish to‘xtaydi.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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
            audioRemote.startListening(familyCode: familyCode, childUid: childUid, consent: audioConsent)
            if locationSharingConsent {
                LocationSync.shared.start(familyCode: familyCode, childUid: childUid)
            }
            if #available(iOS 16.0, *) {
                FocusModeSync.shared.start(familyCode: familyCode, childUid: childUid)
                ScreenTimeController.shared.start(familyCode: familyCode, childUid: childUid)
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                audioConsent.refreshPermission()
                audioRemote.startListening(familyCode: familyCode, childUid: childUid, consent: audioConsent)
                if #available(iOS 16.0, *) {
                    ScreenTimeController.shared.syncQueuedEvents()
                }
            } else {
                audioRemote.stopListening()
            }
        }
    }
}
