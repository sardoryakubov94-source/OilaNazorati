import AVFoundation
import AgoraRtcKit
import Combine
import FirebaseAuth
import FirebaseFirestore
import ReplayKit
import SwiftUI

/// Consent-aware iOS screen sharing. ReplayKit itself is started only by the
/// child tapping Apple's Broadcast picker; a parent request never starts capture silently.
@MainActor
final class ChildScreenShareController: NSObject, ObservableObject, AgoraRtcEngineDelegate {
    @Published private(set) var consentEnabled = false
    @Published private(set) var requestPending = false
    @Published private(set) var isPublishing = false
    @Published private(set) var status = "Ekran ulashish o‘chiq"

    private var listener: ListenerRegistration?
    private var engine: AgoraRtcEngineKit?
    private var familyCode = ""
    private var childUid = ""
    private var requestId = ""
    private var channelName = ""
    private var rtcUid: UInt = 0
    private var pendingJoin = false

    private let appGroup = "group.uz.oilanazorati.shared"

    func configure(familyCode: String, childUid: String) {
        self.familyCode = familyCode
        self.childUid = childUid
        loadConsent()
        listenForRequests()
    }

    func stopListening() {
        listener?.remove()
        listener = nil
        stopPublishing()
    }

    func setConsent(_ enabled: Bool) {
        consentEnabled = enabled
        Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_share_settings").document("current")
            .setData([
                "screenShareConsent": enabled,
                "consentVersion": 1,
                "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000)
            ], merge: true)
        if !enabled { stopPublishing() }
    }

    private func loadConsent() {
        Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_share_settings").document("current")
            .getDocument { [weak self] snapshot, _ in
                Task { @MainActor in self?.consentEnabled = snapshot?.get("screenShareConsent") as? Bool == true }
            }
    }

    private func listenForRequests() {
        listener?.remove()
        listener = Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_share_requests").document("current")
            .addSnapshotListener { [weak self] snapshot, error in
                guard error == nil, let data = snapshot?.data() else { return }
                Task { @MainActor in
                    guard let self else { return }
                    let status = data["status"] as? String ?? ""
                    self.requestPending = status == "requested"
                    if status == "stop_requested" || status == "stopped" || status == "cancelled" {
                        self.stopPublishing()
                    }
                    if status == "requested" && !self.consentEnabled {
                        self.status = "Ota-ona ekran ulashishni so‘radi — rozilik yoqilmagan"
                    } else if status == "requested" {
                        self.status = "Ota-ona so‘rovi keldi. Ekranni ulashish uchun o‘zingiz boshlang."
                    }
                }
            }
    }

    /// Called only from the child's explicit Start Broadcast button.
    func prepareBroadcast(completion: @escaping (Result<Void, Error>) -> Void) {
        guard consentEnabled else {
            completion(.failure(ScreenShareError.consentRequired)); return
        }
        guard let user = Auth.auth().currentUser else {
            completion(.failure(ScreenShareError.notSignedIn)); return
        }
        let ref = Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid).collection("screen_share_requests").document("current")
        ref.getDocument { [weak self] snapshot, error in
            guard let self else { return }
            if let error { completion(.failure(error)); return }
            guard let data = snapshot?.data(), data["status"] as? String == "requested",
                  let rid = data["requestId"] as? String, let channel = data["channelName"] as? String else {
                completion(.failure(ScreenShareError.noRequest)); return
            }
            self.requestId = rid
            self.channelName = channel
            self.rtcUid = UInt.random(in: 1000...UInt(UInt32.max))
            user.getIDToken { token, error in
                if let error { completion(.failure(error)); return }
                guard let token else { completion(.failure(ScreenShareError.notSignedIn)); return }
                self.fetchPublisherToken(idToken: token) { result in
                    Task { @MainActor in
                        switch result {
                        case .failure(let error): completion(.failure(error))
                        case .success(let values):
                            let defaults = UserDefaults(suiteName: self.appGroup)
                            defaults?.set(values.appID, forKey: "screenShareAgoraAppID")
                            defaults?.set(values.token, forKey: "screenShareAgoraToken")
                            defaults?.set(self.channelName, forKey: "screenShareChannel")
                            defaults?.set(self.familyCode, forKey: "screenShareFamilyCode")
                            defaults?.set(self.childUid, forKey: "screenShareChildUid")
                            defaults?.set(self.requestId, forKey: "screenShareRequestID")
                            defaults?.set(token, forKey: "screenShareFirebaseIDToken")
                            defaults?.synchronize()
                            self.joinPublisher(appID: values.appID, token: values.token, completion: completion)
                        }
                    }
                }
            }
        }
    }

    private struct TokenResponse { let appID: String; let token: String }

    private func fetchPublisherToken(idToken: String, completion: @escaping (Result<TokenResponse, Error>) -> Void) {
        guard let url = URL(string: "https://us-central1-oilanazorat-3c8de.cloudfunctions.net/agoraScreenToken") else {
            completion(.failure(ScreenShareError.badEndpoint)); return
        }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "familyCode": familyCode, "childUid": childUid, "requestId": requestId,
            "channelName": channelName, "uid": rtcUid,
            "role": "publisher"
        ])
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error { completion(.failure(error)); return }
            guard let data, let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let appID = json["appId"] as? String, let token = json["token"] as? String else {
                completion(.failure(ScreenShareError.tokenRejected)); return
            }
            completion(.success(TokenResponse(appID: appID, token: token)))
        }.resume()
    }

    private func joinPublisher(appID: String, token: String, completion: @escaping (Result<Void, Error>) -> Void) {
        guard engine == nil else { completion(.success(())); return }
        let config = AgoraRtcEngineConfig()
        config.appId = appID
        let rtc = AgoraRtcEngineKit.sharedEngine(with: config, delegate: self)
        engine = rtc
        rtc.setChannelProfile(.liveBroadcasting)
        rtc.setClientRole(.broadcaster)
        rtc.enableVideo()
        rtc.enableAudio()
        let options = AgoraRtcChannelMediaOptions()
        options.clientRoleType = .broadcaster
        options.publishCameraTrack = false
        options.publishMicrophoneTrack = false
        options.autoSubscribeAudio = false
        options.autoSubscribeVideo = false
        pendingJoin = true
        let result = rtc.joinChannel(byToken: token, channelId: channelName,
                                     uid: rtcUid,
                                     mediaOptions: options, joinSuccess: nil)
        if result != 0 {
            pendingJoin = false
            rtc.leaveChannel(nil)
            AgoraRtcEngineKit.destroy()
            engine = nil
            completion(.failure(ScreenShareError.joinFailed(result)))
        } else {
            completion(.success(()))
        }
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinChannel channel: String, withUid uid: UInt, elapsed: Int) {
        pendingJoin = false
        isPublishing = true
        status = "Broadcast oynasidan ekran uzatishni boshlang"
        Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_share_requests").document("current")
            .updateData(["status": "broadcast_ready", "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000)])
    }

    func stopPublishing() {
        engine?.stopScreenCapture()
        engine?.leaveChannel(nil)
        engine?.disableVideo()
        AgoraRtcEngineKit.destroy()
        engine = nil
        isPublishing = false
        pendingJoin = false
        status = "Ekran uzatish to‘xtatildi"
        if !requestId.isEmpty {
            Firestore.firestore().collection("families").document(familyCode)
                .collection("children").document(childUid)
                .collection("screen_share_requests").document("current")
                .setData(["status": "stopped", "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000)], merge: true)
        }
    }
}

enum ScreenShareError: LocalizedError {
    case consentRequired, notSignedIn, noRequest, badEndpoint, tokenRejected
    case joinFailed(Int)
    var errorDescription: String? {
        switch self {
        case .consentRequired: return "Avval ekran ulashishga rozilik bering."
        case .notSignedIn: return "Bola qurilmasi akkaunti tasdiqlanmagan."
        case .noRequest: return "Faol ota-ona so‘rovi topilmadi."
        case .badEndpoint: return "Ekran ulashish token serveri sozlanmagan."
        case .tokenRejected: return "Ekran ulashish tokeni olinmadi."
        case .joinFailed(let code): return "Ekran kanaliga ulanib bo‘lmadi (\(code))."
        }
    }
}

/// The child must tap Apple's system broadcast confirmation. The app cannot
/// start ReplayKit silently or approve the OS prompt remotely.
struct ScreenBroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: .zero)
        picker.preferredExtension = "uz.oilanazorati.parentcontrol.ScreenShare"
        picker.showsMicrophoneButton = false
        return picker
    }
    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}
