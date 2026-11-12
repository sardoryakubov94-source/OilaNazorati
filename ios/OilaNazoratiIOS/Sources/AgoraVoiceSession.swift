import AVFoundation
import AgoraRtcKit
import FirebaseAuth
import Combine
import Foundation

enum AgoraVoiceError: LocalizedError {
    case missingTokenEndpoint
    case invalidTokenResponse
    case serverRejected(String)
    case notSignedIn
    case joinFailed(Int)

    var errorDescription: String? {
        switch self {
        case .missingTokenEndpoint: return "Xavfsiz Agora token serveri sozlanmagan."
        case .invalidTokenResponse: return "Token serveridan noto‘g‘ri javob olindi."
        case .serverRejected(let code):
            switch code {
            case "premium_required": return "Jonli ovoz Premium obunasi bilan ishlaydi."
            case "child_consent_required": return "Bola qurilmasida jonli ovozga rozilik yoqilmagan."
            case "voice_request_expired": return "Ovoz so‘rovining muddati tugagan. Qayta urinib ko‘ring."
            default: return "Xavfsiz ovoz serveri so‘rovni rad etdi."
            }
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
    @Published private(set) var isConnecting = false
    @Published private(set) var statusText = "Ulanmagan"

    private var engine: AgoraRtcEngineKit?
    private var activeChannel: String?
    private var isPublisher = false
    private var pendingJoinCompletion: ((Result<Void, Error>) -> Void)?

    func join(channel: String, role: String, familyCode: String, childUid: String, requestID: String,
              completion: @escaping (Result<Void, Error>) -> Void) {
        guard !isConnected, !isConnecting else { return }
        isConnecting = true
        guard let endpointString = Bundle.main.object(forInfoDictionaryKey: "AGORA_TOKEN_ENDPOINT") as? String,
              let endpoint = URL(string: endpointString), endpoint.scheme == "https" else {
            isConnecting = false
            completion(.failure(AgoraVoiceError.missingTokenEndpoint))
            return
        }
        guard let user = Auth.auth().currentUser else {
            isConnecting = false
            completion(.failure(AgoraVoiceError.notSignedIn))
            return
        }

        let uid = UInt.random(in: 1000...UInt(UInt32.max))
        statusText = "Xavfsiz ulanish tayyorlanmoqda…"
        user.getIDToken { [weak self] idToken, error in
            guard let self else { return }
            if let error {
                Task { @MainActor in self.isConnecting = false; completion(.failure(error)) }
                return
            }
            guard let idToken else {
                Task { @MainActor in self.isConnecting = false; completion(.failure(AgoraVoiceError.notSignedIn)) }
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
                    Task { @MainActor in self.isConnecting = false; completion(.failure(requestError)) }
                    return
                }
                guard let http = response as? HTTPURLResponse, let data else {
                    Task { @MainActor in self.isConnecting = false; completion(.failure(AgoraVoiceError.invalidTokenResponse)) }
                    return
                }
                guard (200..<300).contains(http.statusCode) else {
                    let errorCode = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
                    Task { @MainActor in
                        self.isConnecting = false
                        completion(.failure(errorCode.map(AgoraVoiceError.serverRejected) ?? AgoraVoiceError.invalidTokenResponse))
                    }
                    return
                }
                guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let token = json["token"] as? String,
                      let appID = json["appId"] as? String,
                      !token.isEmpty, !appID.isEmpty else {
                    Task { @MainActor in self.isConnecting = false; completion(.failure(AgoraVoiceError.invalidTokenResponse)) }
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
                isConnecting = false
                completion(.failure(error))
                return
            }
            rtc.setClientRole(.broadcaster)
        } else {
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.allowBluetooth])
                try AVAudioSession.sharedInstance().setActive(true)
            } catch {
                isConnecting = false
                completion(.failure(error))
                return
            }
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
        pendingJoinCompletion = completion
        let result = rtc.joinChannel(byToken: token, channelId: channel, uid: uid,
                                     mediaOptions: options, joinSuccess: nil)
        if result != 0 {
            pendingJoinCompletion = nil
            isConnecting = false
            statusText = "Ulanish amalga oshmadi"
            completion(.failure(AgoraVoiceError.joinFailed(Int(result))))
        }
    }

    func leave() {
        pendingJoinCompletion = nil
        engine?.leaveChannel(nil)
        engine?.disableAudio()
        AgoraRtcEngineKit.destroy()
        engine = nil
        activeChannel = nil
        isConnected = false
        isConnecting = false
        statusText = "Ulanmagan"
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        isPublisher = false
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinChannel channel: String, withUid uid: UInt, elapsed: Int) {
        guard activeChannel == channel else { return }
        isConnected = true
        isConnecting = false
        statusText = isPublisher ? "Ovoz uzatilmoqda" : "Ota-ona tinglamoqda"
        let completion = pendingJoinCompletion
        pendingJoinCompletion = nil
        completion?(.success(()))
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didOccurError errorCode: AgoraErrorCode) {
        guard let completion = pendingJoinCompletion else { return }
        pendingJoinCompletion = nil
        isConnecting = false
        completion(.failure(AgoraVoiceError.joinFailed(Int(errorCode.rawValue))))
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didLeaveChannelWith stats: AgoraChannelStats) {
        isConnected = false
        isConnecting = false
        statusText = "Ulanish tugadi"
    }

}
