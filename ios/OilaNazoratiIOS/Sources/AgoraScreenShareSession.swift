import SwiftUI
import UIKit
import AgoraRtcKit
import FirebaseAuth
import FirebaseFirestore
import ReplayKit

/// Separate Agora video session: voice sessions remain untouched.
@MainActor
final class AgoraScreenShareSession: NSObject, ObservableObject, AgoraRtcEngineDelegate {
    @Published var status = "Ekran ulashishga tayyor"
    @Published var isJoined = false
    @Published var remoteScreenUID: UInt?
    @Published var isBroadcasting = false
    private var engine: AgoraRtcEngineKit?
    private var mediaOptions: AgoraRtcChannelMediaOptions?
    private var role = ""
    private var request: (familyCode: String, childUid: String, requestId: String, channel: String)?
    private weak var renderView: UIView?

    func start(familyCode: String, childUid: String, requestId: String, channel: String,
               role: String, renderView: UIView? = nil, completion: @escaping (Result<Void, Error>) -> Void) {
        guard !isJoined else { completion(.success(())); return }
        guard let user = Auth.auth().currentUser else {
            completion(.failure(ScreenShareError.notSignedIn)); return
        }
        self.role = role
        self.request = (familyCode, childUid, requestId, channel)
        self.renderView = renderView
        user.getIDToken { [weak self] idToken, error in
            guard let self, let idToken, error == nil else {
                Task { @MainActor in completion(.failure(ScreenShareError.notSignedIn)) }; return
            }
            var req = URLRequest(url: URL(string: "https://us-central1-oilanazorat-3c8de.cloudfunctions.net/agoraVoiceToken")!)
            req.httpMethod = "POST"
            req.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let uid = role == "screen_publisher" ? 2 : 1
            req.httpBody = try? JSONSerialization.data(withJSONObject: [
                "purpose": "screen", "familyCode": familyCode, "childUid": childUid,
                "requestId": requestId, "channelName": channel, "uid": uid, "role": role
            ])
            URLSession.shared.dataTask(with: req) { data, response, error in
                guard error == nil, let data,
                      (response as? HTTPURLResponse)?.statusCode == 200,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let token = json["token"] as? String, let appID = json["appId"] as? String else {
                    Task { @MainActor in completion(.failure(error ?? ScreenShareError.tokenFailed)) }; return
                }
                Task { @MainActor in
                    let config = AgoraRtcEngineConfig()
                    config.appId = appID
                    config.channelProfile = .liveBroadcasting
                    let rtc = AgoraRtcEngineKit.sharedEngine(with: config, delegate: self)
                    self.engine = rtc
                    rtc.setClientRole(role == "screen_publisher" ? .broadcaster : .audience)
                    rtc.enableVideo()
                    let options = AgoraRtcChannelMediaOptions()
                    options.clientRoleType = role == "screen_publisher" ? .broadcaster : .audience
                    options.publishCameraTrack = false
                    options.publishMicrophoneTrack = false
                    options.publishScreenCaptureVideo = role == "screen_publisher"
                    options.publishScreenCaptureAudio = false
                    options.autoSubscribeAudio = false
                    options.autoSubscribeVideo = role == "screen_subscriber"
                    self.mediaOptions = options
                    let result = rtc.joinChannel(byToken: token, channelId: channel,
                        uid: UInt(uid), mediaOptions: options)
                    guard result == 0 else {
                        rtc.leaveChannel(nil); AgoraRtcEngineKit.destroy(); self.engine = nil
                        completion(.failure(ScreenShareError.joinFailed(result))); return
                    }
                    self.status = role == "screen_publisher" ? "Ekran kanaliga ulanmoqda…" : "Bola ekraniga ulanmoqda…"
                    completion(.success(()))
                }
            }.resume()
        }
    }

    func beginCapture() {
        guard role == "screen_publisher", let engine else { return }
        let params = AgoraScreenCaptureParameters2()
        params.captureVideo = true
        params.captureAudio = false
        let video = AgoraScreenVideoParameters()
        video.dimensions = CGSize(width: 720, height: 1280)
        video.frameRate = AgoraVideoFrameRate.fps15.rawValue
        video.bitrate = AgoraVideoBitrateStandard
        params.videoParams = video
        engine.startScreenCapture(params)
        status = "iOS Broadcast oynasida uzatishni tasdiqlang"
    }

    func setRemoteView(_ view: UIView) { renderView = view }

    func stopCapture() {
        engine?.stopScreenCapture()
        engine?.leaveChannel(nil)
        AgoraRtcEngineKit.destroy()
        engine = nil
        mediaOptions = nil
        isJoined = false
        isBroadcasting = false
        remoteScreenUID = nil
        status = "Ekran uzatish to‘xtatildi"
    }


    func rtcEngine(_ engine: AgoraRtcEngineKit,
                   localVideoStateChangedOf state: AgoraVideoLocalState,
                   reason: AgoraLocalVideoStreamReason,
                   sourceType: AgoraVideoSourceType) {
        if sourceType == .screen && state == .stopped {
            if let mediaOptions {
                mediaOptions.publishScreenCaptureVideo = false
                mediaOptions.publishScreenCaptureAudio = false
                engine.updateChannel(with: mediaOptions)
            }
            isBroadcasting = false
            status = "Ekran uzatish to‘xtadi"
            if let request {
                Firestore.firestore().collection("families").document(request.familyCode)
                    .collection("children").document(request.childUid)
                    .collection("screen_requests").document("current")
                    .updateData(["status": "stopped", "stoppedAtMs": Int64(Date().timeIntervalSince1970 * 1000)])
            }
        } else if sourceType == .screen && state == .capturing {
            mediaOptions?.publishScreenCaptureVideo = true
            mediaOptions?.publishScreenCaptureAudio = false
            if let mediaOptions { engine.updateChannel(with: mediaOptions) }
            isBroadcasting = true
            status = "Ekran uzatilmoqda"
        }
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinChannel channel: String, withUid uid: UInt, elapsed: Int) {
        isJoined = true
        status = role == "screen_publisher" ? "Ekran kanaliga ulandi" : "Ekran kanaliga ulandi"
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinedOfUid uid: UInt, elapsed: Int) {
        guard (501...1000).contains(uid), let renderView else { return }
        remoteScreenUID = uid
        let canvas = AgoraRtcVideoCanvas()
        canvas.uid = uid
        canvas.view = renderView
        canvas.renderMode = .hidden
        engine.setupRemoteVideo(canvas)
        status = "Farzand ekrani jonli"
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didOfflineOfUid uid: UInt, reason: AgoraUserOfflineReason) {
        guard remoteScreenUID == uid else { return }
        let canvas = AgoraRtcVideoCanvas()
        canvas.uid = uid
        canvas.view = nil
        engine.setupRemoteVideo(canvas)
        remoteScreenUID = nil
        status = "Ekran uzatish tugadi"
    }
}

enum ScreenShareError: LocalizedError {
    case notSignedIn, tokenFailed, joinFailed(Int)
    var errorDescription: String? {
        switch self {
        case .notSignedIn: return "Hisob qayta tekshirilmadi."
        case .tokenFailed: return "Ekran uchun xavfsiz Agora tokeni olinmadi."
        case .joinFailed(let code): return "Agora ekran kanali xatosi: \(code)"
        }
    }
}

struct AgoraRemoteScreenView: UIViewRepresentable {
    let session: AgoraScreenShareSession
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        session.setRemoteView(view)
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) { session.setRemoteView(uiView) }
}

struct IOSBroadcastPicker: UIViewRepresentable {
    var preferredExtension: String
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: .zero)
        picker.preferredExtension = preferredExtension
        picker.showsMicrophoneButton = false
        return picker
    }
    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}
