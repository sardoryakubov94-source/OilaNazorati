import ReplayKit
import AgoraRtcKit
import CoreVideo

/// ReplayKit's system-owned broadcast process. Capture begins only after
/// the child explicitly starts the iOS Broadcast picker.
final class SampleHandler: RPBroadcastSampleHandler, AgoraRtcEngineDelegate {
    private var engine: AgoraRtcEngineKit?
    private var joined = false
    private let shared = UserDefaults(suiteName: "group.uz.oilanazorati.shared")

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        guard let appID = shared?.string(forKey: "screen_agora_app_id"),
              let token = shared?.string(forKey: "screen_agora_token"),
              let channel = shared?.string(forKey: "screen_agora_channel"),
              let rawUID = shared?.integer(forKey: "screen_agora_uid"),
              !appID.isEmpty, !token.isEmpty, !channel.isEmpty else {
            finishBroadcastWithError(NSError(domain: "OilaNazorati.ScreenShare", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Ekran uzatish sozlamalari topilmadi. Ilovadan qayta boshlang."]))
            return
        }
        let config = AgoraRtcEngineConfig()
        config.appId = appID
        let rtc = AgoraRtcEngineKit.sharedEngine(with: config, delegate: self)
        engine = rtc
        rtc.setChannelProfile(.liveBroadcasting)
        rtc.setClientRole(.broadcaster)
        rtc.enableVideo()
        rtc.setVideoEncoderConfiguration(AgoraVideoEncoderConfiguration(
            size: CGSize(width: 1280, height: 720),
            frameRate: .fps15, bitrate: AgoraVideoEncoderConfigurationStandard,
            orientationMode: .adaptative
        ))
        rtc.setExternalVideoSource(true, useTexture: false, pushMode: true)
        let options = AgoraRtcChannelMediaOptions()
        options.clientRoleType = .broadcaster
        options.publishCameraTrack = false
        options.publishMicrophoneTrack = false
        options.publishCustomVideoTrack = true
        options.autoSubscribeAudio = false
        options.autoSubscribeVideo = false
        let result = rtc.joinChannel(byToken: token, channelId: channel, uid: UInt(rawUID),
                                     mediaOptions: options, joinSuccess: nil)
        if result != 0 {
            finishBroadcastWithError(NSError(domain: "OilaNazorati.ScreenShare", code: result,
                userInfo: [NSLocalizedDescriptionKey: "Agora ekran kanaliga ulanib bo‘lmadi (\(result))."]))
        }
    }

    override func broadcastPaused() {}
    override func broadcastResumed() {}

    override func broadcastFinished() {
        engine?.leaveChannel(nil)
        AgoraRtcEngineKit.destroy()
        engine = nil
        joined = false
        shared?.removeObject(forKey: "screen_agora_token")
        shared?.set(false, forKey: "screen_broadcast_active")
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer,
                                     with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let engine, joined || engine != nil,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let frame = AgoraVideoFrame()
        frame.format = AgoraVideoFormat.cvPixelBuffer
        frame.textureBuf = pixelBuffer
        frame.time = Int64(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer)) * 1000)
        frame.rotation = 0
        _ = engine.pushExternalVideoFrame(frame)
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didJoinChannel channel: String,
                   withUid uid: UInt, elapsed: Int) {
        joined = true
        shared?.set(true, forKey: "screen_broadcast_active")
    }

    func rtcEngine(_ engine: AgoraRtcEngineKit, didOccurError errorCode: AgoraErrorCode) {
        finishBroadcastWithError(NSError(domain: "OilaNazorati.ScreenShare", code: Int(errorCode.rawValue),
            userInfo: [NSLocalizedDescriptionKey: "Agora ekran uzatish xatosi: \(errorCode.rawValue)"]))
    }
}