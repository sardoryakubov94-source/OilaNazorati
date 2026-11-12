import SwiftUI

struct StatusView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var locationSync = LocationSync.shared
    let familyCode: String
    let childUid: String
    @AppStorage("location_sharing_consent") private var locationSharingConsent = false
    @StateObject private var audioConsent = ChildAudioConsent()
    @StateObject private var audioRemote = ChildAudioRemoteController()
    @StateObject private var screenShare = ChildScreenShareController()
    @State private var showScreenBroadcastPicker = false
    @State private var screenShareError: String?


    private func saveRemoteAudioConsent(_ enabled: Bool) {
        Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("mic_settings").document("current")
            .setData([
                "remoteAudioConsent": enabled,
                "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000),
                "consentVersion": 1
            ], merge: true)
    }

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
                                        saveRemoteAudioConsent(true)
                                        audioRemote.startListening(familyCode: familyCode, childUid: childUid, consent: audioConsent)
                                    } else {
                                        audioConsent.disable()
                                        saveRemoteAudioConsent(false)
                                        audioRemote.stopListening()
                                    }
                                }
                            } else {
                                audioConsent.disable()
                                saveRemoteAudioConsent(false)
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
                Section("Ekran ulashish") {
                    Toggle("Ekran ulashishga oldindan rozilik", isOn: Binding(
                        get: { screenShare.consentEnabled },
                        set: { screenShare.setConsent($0) }
                    ))
                    .tint(.blue)
                    Label(screenShare.status, systemImage: screenShare.isPublishing ? "rectangle.inset.filled" : "rectangle.on.rectangle")
                        .font(.footnote).foregroundStyle(screenShare.isPublishing ? .green : .secondary)
                    if screenShare.requestPending && screenShare.consentEnabled {
                        Button {
                            audioRemote.stopPublishing() // Agora engine is shared; screen and mic publishers cannot run concurrently.\n                            screenShare.prepareBroadcast { result in
                                DispatchQueue.main.async {
                                    switch result {
                                    case .success: showScreenBroadcastPicker = true
                                    case .failure(let error): screenShareError = error.localizedDescription
                                    }
                                }
                            }
                        } label: { Label("Ota-ona so‘roviga ekran ulashish", systemImage: "dot.radiowaves.left.and.right") }
                        .disabled(screenShare.isPublishing)
                    }
                    if showScreenBroadcastPicker {
                        ScreenBroadcastPicker().frame(height: 52)
                        Text("Tizim oynasida Oila Nazoratini tanlab, Broadcast boshlashni tasdiqlang. Ekran uzatilganda iOS qizil indikator ko‘rsatadi.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if screenShare.isPublishing {
                        Button(role: .destructive) {
                            screenShare.stopPublishing()
                            showScreenBroadcastPicker = false
                        } label: { Label("Ekran uzatishni to‘xtatish", systemImage: "stop.circle") }
                    }
                    if let screenShareError {
                        Text(screenShareError).font(.footnote).foregroundStyle(.red)
                    }
                    Text("Ekran faqat bolaning roziligi va iOS Broadcast tasdig‘i bilan uzatiladi. To‘xtatish uchun ilovadagi tugma va iOS qizil Broadcast indikatoridan foydalaning.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    Text("Apple Screen Time funksiyalari bolaning qurilmasidagi Family Controls ruxsati va ilova tanlovidan keyin ishlaydi. Android'dagi SMS, qo‘ng‘iroq tarixi, bildirishnoma matni, ekran surati va yashirin fon mikrofoni iOS’da mavjud emas. Jonli ovoz faqat yuqoridagi alohida rozilik va faol sessiya orqali ishlaydi.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Bola qurilmasi")
        }
        .onDisappear {
            audioRemote.stopListening()
            screenShare.stopListening()
        }
        .onAppear {
            saveRemoteAudioConsent(audioConsent.isEnabled)
            audioRemote.startListening(familyCode: familyCode, childUid: childUid, consent: audioConsent)
            screenShare.configure(familyCode: familyCode, childUid: childUid)
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
                let hadAudioConsent = audioConsent.isEnabled
                audioConsent.refreshPermission()
                if hadAudioConsent && !audioConsent.isEnabled { saveRemoteAudioConsent(false) }
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