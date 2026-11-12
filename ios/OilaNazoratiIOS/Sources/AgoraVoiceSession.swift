import AVFoundation
import AgoraRtcKit
import FirebaseAuth
import Foundation

enum AgoraVoiceError: LocalizedError {
    case missingTokenEndpoint
    case invalidTokenResponse
    case notSignedIn
    case joinFailed(Int)

    var errorDescription: String? {
        switch self {
        case .missingTokenEndpoint: return "Xavfsiz Agora token serveri sozlanmagan."
        case .invalidTokenResponse: return "Token serveridan noto‘g‘ri javob olindi."
        case .notSignedIn: return "Qurilma hisobi tasdiqlanmagan."
        case .joinFailed(let code): return "Ovoz kanaliga ulanib bo‘lmadi (\(code))."
        }
    }
}

/// Agora orqali faqat audio kanaliga ulanadi. App Certificate hech qachon
/// ilovaga joylanmaydi: token faqat autentifikatsiyalangan serverdan olinadi.
@MainActor
final class AgoraVoiceSession: NSObject, ObservableObject, AgoraRtcEngineDelegate {
    @Published private(set) var isConnected = false
    @Published private(set) var statusText = "Ulanmagan"

    private var engine: AgoraRtcEngineKit?
    private var activeChannel: String?
    private var isPublisher = false

    func join(channel: String, role: String, familyCode: String, childUid: String, requestID: String,
              completion: @escaping (Result<Void, Error>) -> Void) {
        guard let endpointString = Bundle.main.object(forInfoDictionaryKey: "AGORA_TOKEN_ENDPOINT") as? String,
              let endpoint = URL(string: endpointString), endpoint.scheme == "https" else {
            completion(.failure(AgoraVoiceError.missingTokenEndpoint))
            return
        }
        guard let user = Auth.auth().currentUser else {
            completion(.failure(AgoraVoiceError.notSignedIn))
            return
        }

        let uid = UInt.random(in: 1000...UInt(UInt32.max))
        statusText = "Xavfsiz ulanish tayyorlanmoqda…"
        user.getIDToken { [weak self] idToken, error in
            guard let self else { return }
            if let error {
                Task { @MainActor in completion(.failure(error)) }
                return
            }
            guard let idToken else {
                Task { @MainActor in completion(.failure(AgoraVoiceError.notSignedIn)) }
                return
            }

            var request = URLRequest(url: endpoint, timeoutInterval: 12)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
            request.httpBody = try? JSONSerialization.data(withJSONObject: [
                "channelName": channel,
                "uid": uid,
                "role": role,
                "familyCode": familyCode,
                "childUid": childUid,
                "requestId": requestID
            ])

            URLSession.shared.dataTask(with: request) { data, response, requestError in
                if let requestError {
                    Task { @MainActor in completion(.failure(requestError)) }
                    return
                }
                guard let http = response as? HTTPURLResponse,
                      (200..<300).contains(http.statusCode),
                      let data,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let token = json["token"] as? String,
                      let appID = json["appId"] as? String,
                      !token.isEmpty, !appID.isEmpty else {
                    Task { @MainActor in completion(.failure(AgoraVoiceError.invalidTokenResponse)) }
                    return
                }
                Task { @MainActor in
                    self.joinAgora(appID: appID, channel: channel, uid: uid, token: token, role: role, completion: completion)
                }
            }.resume()
        }
    }

    private func joinAgora(appID: String, channel: String, uid: UInt, token: String,
                           role: String, completion: @escaping (Result<Void, Error>) -> Void) {
        let publisher = role == "publisher"
        isPublisher = publisher

        let config = AgoraRtcEngineConfig()
        config.appId = appID
        let rtc = AgoraRtcEngineKit.sharedEngine(with: config, delegate: self)
        engine = rtc
        rtc.setChannelProfile(.liveBroadcasting)
        rtc.enableAudio()

        if publisher {
            let audioSession = AVAudioSession.sharedInstance()
            do {
                try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
                try audioSession.setActive(true)
            } catch {
                completion(.failure(error))
                return
            }
            rtc.setClientRole(.broadcaster)
        } else {
            rtc.setClientRole(.audience)
        }

        let options = AgoraRtcChannelMediaOptions()
        options.clientRoleType = publisher ? .broadcaster : .audience
        options.publishMicrophoneTrack = publisher
        options.publishCameraTrack = false
        options.autoSubscribeAudio = !publisher
        options.autoSubscribeVideo = false

        activeChannel = channel
        statusText = "Ovoz kanaliga ulanmoqda…"
        let result = rtc.joinChannel(byToken: token, channelId: channel, uid: uid,
                                     mediaOptions: options, joinSuccess: nil)
        if result != 0 {
            statusText = "Ulanish amalga oshmadi"
            completion(.failure(AgoraVoiceError.joinFailed(Int(result))))
        } else {
            completion(.success(()))
        }
    }

    func leave() {
        engine?.leaveChannel(nil)
        engine?.disableAudio()
        AgoraRtcEngineKit.destroy()
        engine = nil
        activeChannel = nil
        isConnected = false
        statusText = "Ulanmagan"
        if isPublisher {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        isPublisher = false
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinChannel channel: String, withUid uid: UInt, elapsed: Int) {
        isConnected = true
        statusText = isPublisher ? "Ovoz uzatilmoqda" : "Ota-ona tinglamoqda"
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didLeaveChannelWith stats: AgoraChannelStats) {
        isConnected = false
        statusText = "Ulanish tugadi"
    }

    deinit {
        engine?.leaveChannel(nil)
    }
}
