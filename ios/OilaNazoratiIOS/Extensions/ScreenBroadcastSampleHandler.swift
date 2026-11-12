import ReplayKit
import AgoraReplayKitExtension

/// Agora's ReplayKit bridge handles video frames in the extension process.
/// iOS displays its own broadcast indicator and the child can stop sharing
/// at any time from the system's recording control.
final class SampleHandler: RPBroadcastSampleHandler, AgoraReplayKitExtDelegate {
    private let appGroup = "group.uz.oilanazorati.shared"

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        AgoraReplayKitExt.shareInstance().start(self)
    }

    override func broadcastPaused() {
        AgoraReplayKitExt.shareInstance().pause()
    }

    override func broadcastResumed() {
        AgoraReplayKitExt.shareInstance().resume()
    }

    override func broadcastFinished() {
        AgoraReplayKitExt.shareInstance().stop()
        UserDefaults(suiteName: appGroup)?.set(false, forKey: "screen_broadcast_active")
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer,
                                     with sampleBufferType: RPSampleBufferType) {
        AgoraReplayKitExt.shareInstance().push(sampleBuffer, with: sampleBufferType)
    }

    func broadcastFinished(_ broadcast: AgoraReplayKitExt, reason: AgoraReplayKitExtReason) {
        let message: String
        switch reason {
        case .initiativeStop: message = "Ekran uzatish to‘xtatildi"
        case .connectFail: message = "Ekran uzatish ulanishi uzildi"
        case .disconnect: message = "Ekran uzatish serverdan uzildi"
        @unknown default: message = "Ekran uzatish yakunlandi"
        }
        UserDefaults(suiteName: appGroup)?.set(false, forKey: "screen_broadcast_active")
        finishBroadcastWithError(NSError(domain: "OilaNazorati.ScreenShare", code: 0,
            userInfo: [NSLocalizedDescriptionKey: message]))
    }
}