import ReplayKit
import AgoraReplayKitExtension

/// Apple's ReplayKit process plus Agora's supported ReplayKit bridge.
/// The OS broadcast picker is the only entry point; no hidden capture.
final class SampleHandler: AgoraReplayKitHandler {
    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        super.broadcastStarted(withSetupInfo: setupInfo)
    }
    override func broadcastPaused() {
        super.broadcastPaused()
    }
    override func broadcastResumed() {
        super.broadcastResumed()
    }
    override func broadcastFinished() {
        super.broadcastFinished()
        UserDefaults(suiteName: "group.uz.oilanazorati.shared")?.set(false, forKey: "screen_broadcast_active")
    }
    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer,
                                     with sampleBufferType: RPSampleBufferType) {
        super.processSampleBuffer(sampleBuffer, with: sampleBufferType)
    }
}