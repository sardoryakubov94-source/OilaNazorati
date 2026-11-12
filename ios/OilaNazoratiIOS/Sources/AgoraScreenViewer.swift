import AgoraRtcKit
import Combine
import FirebaseAuth
import SwiftUI
import UIKit

@MainActor
final class AgoraScreenViewer: NSObject, ObservableObject, AgoraRtcEngineDelegate {
    @Published private(set) var connected = false
    @Published private(set) var status = "Ekran uzatish kutilmoqda"
    let remoteView = UIView()
    private var engine: AgoraRtcEngineKit?
    private var channel: String?
    private var pendingRequestID: String?

    func join(channel: String, familyCode: String, childUid: String, requestID: String) {
        guard self.channel != channel, engine == nil else { return }
        self.channel = channel
        pendingRequestID = requestID
        guard let user = Auth.auth().currentUser else { status = "Ota-ona hisobi aniqlanmadi"; return }
        user.getIDToken { [weak self] idToken, error in
            guard let self else { return }
            if let error { Task { @MainActor in self.status = error.localizedDescription }; return }
            guard let idToken else { Task { @MainActor in self.status = "Auth token olinmadi" }; return }
            let uid = UInt.random(in: 1000...UInt(UInt32.max))
            var request = URLRequest(url: URL(string: "https://us-central1-oilanazorat-3c8de.cloudfunctions.net/agoraScreenToken")!, timeoutInterval: 15)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
            request.httpBody = try? JSONSerialization.data(withJSONObject: [
                "familyCode": familyCode, "childUid": childUid, "requestId": requestID,
                "channelName": channel, "uid": uid, "role": "subscriber"
            ])
            URLSession.shared.dataTask(with: request) { data, response, error in
                if let error { Task { @MainActor in self.status = error.localizedDescription }; return }
                guard let data, let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let token = json["token"] as? String, let appID = json["appId"] as? String else {
                    Task { @MainActor in self.status = "Ekran tokeni olinmadi" }; return
                }
                Task { @MainActor in self.startEngine(appID: appID, token: token, channel: channel, uid: uid) }
            }.resume()
        }
    }

    private func startEngine(appID: String, token: String, channel: String, uid: UInt) {
        let config = AgoraRtcEngineConfig()
        config.appId = appID
        let rtc = AgoraRtcEngineKit.sharedEngine(with: config, delegate: self)
        engine = rtc
        rtc.setChannelProfile(.liveBroadcasting)
        rtc.setClientRole(.audience)
        rtc.enableVideo()
        let options = AgoraRtcChannelMediaOptions()
        options.clientRoleType = .audience
        options.publishCameraTrack = false
        options.publishMicrophoneTrack = false
        options.autoSubscribeVideo = true
        options.autoSubscribeAudio = false
        status = "Ekran kanaliga ulanmoqda…"
        let result = rtc.joinChannel(byToken: token, channelId: channel, uid: uid, mediaOptions: options, joinSuccess: nil)
        if result != 0 {
            status = "Ulanish xatosi: \(result)"
            leave()
        }
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinedOfUid uid: UInt, elapsed: Int) {
        let canvas = AgoraRtcVideoCanvas()
        canvas.uid = uid
        canvas.view = remoteView
        canvas.renderMode = .hidden
        engine.setupRemoteVideo(canvas)
        connected = true
        status = "Farzand ekrani jonli uzatilmoqda"
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didOfflineOfUid uid: UInt, reason: AgoraUserOfflineReason) {
        connected = false
        status = "Bola ekran uzatishni to‘xtatdi"
        leave()
    }

    func leave() {
        engine?.setupRemoteVideo(nil)
        engine?.leaveChannel(nil)
        engine?.disableVideo()
        AgoraRtcEngineKit.destroy()
        engine = nil
        channel = nil
        pendingRequestID = nil
        connected = false
    }
}

struct AgoraScreenVideoSurface: UIViewRepresentable {
    @ObservedObject var viewer: AgoraScreenViewer
    func makeUIView(context: Context) -> UIView {
        viewer.remoteView.backgroundColor = .black
        viewer.remoteView.contentMode = .scaleAspectFit
        return viewer.remoteView
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
