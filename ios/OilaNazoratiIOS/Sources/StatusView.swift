import SwiftUI
import FirebaseFirestore

struct StatusView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var locationSync = LocationSync.shared
    let familyCode: String
    let childUid: String
    @AppStorage("location_sharing_consent") private var locationSharingConsent = false
    @StateObject private var audioConsent = ChildAudioConsent()
    @StateObject private var audioRemote = ChildAudioRemoteController()
    @StateObject private var screenShare = AgoraScreenShareSession()
    @State private var screenRequestListener: ListenerRegistration?
    @State private var screenRequestID: String?
    @State private var screenChannel: String?
    @State private var screenRequested = false
    @State private var showBroadcastPicker = false


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
                Section("Jonli ekran") {
                    Label(screenShare.status, systemImage: screenShare.isJoined ? "rectangle.on.rectangle" : "rectangle")
                        .font(.footnote).foregroundStyle(screenShare.isJoined ? .green : .secondary)
                    if screenRequested, let requestID = screenRequestID, let channel = screenChannel {
                        Button {
                            screenShare.start(familyCode: familyCode, childUid: childUid,
                                requestId: requestID, channel: channel, role: "screen_publisher") { result in
                                switch result {
                                case .success:
                                    screenShare.beginCapture()
                                    showBroadcastPicker = true
                                    Firestore.firestore().collection("families").document(familyCode)
                                        .collection("children").document(childUid)
                                        .collection("screen_requests").document("current")
                                        .updateData(["status": "active", "childStartedAtMs": Int64(Date().timeIntervalSince1970 * 1000)])
                                case .failure(let error):
                                    screenShare.status = error.localizedDescription
                                }
                            }
                        } label: {
                            Label("Ekran uzatishni boshlash", systemImage: "record.circle")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    if showBroadcastPicker {
                        IOSBroadcastPicker(preferredExtension: "uz.oilanazorati.parentcontrol.ScreenBroadcast")
                            .frame(width: 64, height: 64)
                        Text("ReplayKit tugmasini bosing va iOS oynasida «Broadcast»ni boshlang. Qizil indikator ekran uzatilayotganini bildiradi.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if screenShare.isJoined {
                        Button(role: .destructive) {
                            screenShare.stopCapture()
                            showBroadcastPicker = false
                            Firestore.firestore().collection("families").document(familyCode)
                                .collection("children").document(childUid)
                                .collection("screen_requests").document("current")
                                .updateData(["status": "stopped", "stoppedAtMs": Int64(Date().timeIntervalSince1970 * 1000)])
                        } label: { Label("Ekran uzatishni to‘xtatish", systemImage: "stop.circle") }
                    }
                    Text("Ekran faqat ota-ona so‘rovi va bolaning iOS Broadcast tasdig‘i bilan uzatiladi. Bola iOS tizimidagi indikator orqali uzatishni istalgan vaqtda to‘xtatishi mumkin.")
                        .font(.footnote).foregroundStyle(.secondary)
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
            screenRequestListener?.remove()
            screenShare.stopCapture()
        }
        .onAppear {
            saveRemoteAudioConsent(audioConsent.isEnabled)
            audioRemote.startListening(familyCode: familyCode, childUid: childUid, consent: audioConsent)
            startScreenRequestListener()
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
                startScreenRequestListener()
                if #available(iOS 16.0, *) {
                    ScreenTimeController.shared.syncQueuedEvents()
                }
            } else {
                audioRemote.stopListening()
                screenRequestListener?.remove()
            }
        }
    }
    
    private func startScreenRequestListener() {
        screenRequestListener?.remove()
        screenRequestListener = Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_requests").document("current")
            .addSnapshotListener { snapshot, _ in
                guard let data = snapshot?.data(), let status = data["status"] as? String else {
                    screenRequested = false
                    return
                }
                screenRequestID = data["requestId"] as? String
                screenChannel = data["channelName"] as? String
                screenRequested = status == "requested"
                if status == "stop_requested" || status == "cancelled" || status == "expired" {
                    screenShare.stopCapture()
                    showBroadcastPicker = false
                    snapshot?.reference.updateData(["status": "stopped", "stoppedAtMs": Int64(Date().timeIntervalSince1970 * 1000)])
                }
            }
    }

}
