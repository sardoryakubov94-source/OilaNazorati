import ReplayKit
import AgoraReplayKitExtension

/// iOS screen frames are captured only after the user explicitly starts
/// the system Broadcast. Agora's ReplayKit bridge handles frame encoding.
final class SampleHandler: AgoraReplayKitHandler {
    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        super.broadcastStarted(withSetupInfo: setupInfo)
    }
}
