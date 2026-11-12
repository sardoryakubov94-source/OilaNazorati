# Screen sharing — verification checklist

This branch adds an iOS ReplayKit Broadcast Upload Extension and Agora screen-video path.
It does not replace Android's existing MediaProjection/Accessibility screenshot code or the
Agora microphone service.

## Supported live-screen path in this branch

- iOS child → iOS parent: child consent + system Broadcast confirmation, Agora publisher,
  iOS parent Agora subscriber/view.
- iOS child → Android parent: same child ReplayKit/Agora publisher; Android
  `IOSScreenShareViewerActivity` requests a subscriber token and renders remote video.
- Android child: existing screenshot/MediaProjection request flow remains unchanged.
  Android-to-Android periodic and on-demand screenshots must be regression-tested.
- Parent requests never start ReplayKit silently. Child must open OilaNazorati, accept the
  in-app request, and manually start the iOS Broadcast picker. iOS red recording indicator
  remains visible.

## Required pre-release checks

1. Deploy Firestore rules and both HTTPS functions (`agoraVoiceToken`,
   `agoraScreenToken`) to `oilanazorat-3c8de`; set the Agora App ID to the exact Android
   project App ID and store the certificate only in Firebase Secret Manager.
2. Build the XcodeGen project on macOS with the ScreenShare extension target included.
3. Build Android Debug APK with the existing Agora SDK.
4. On real iPhone/iPad (not Simulator), enable child screen-share consent, send a parent
   request, tap the in-app start control, and confirm OilaNazorati in Apple's Broadcast UI.
5. Verify video appears on iOS parent and Android parent; verify pause/resume, child stops
   broadcast from iOS indicator/Control Center, parent stop request, app crash, network loss,
   request expiry, child selection change, and parent sign-out.
6. Verify disabled consent, non-Premium parent, mismatched child/family, expired request,
   and non-owner token requests are rejected.
7. Regression-test Android→Android microphone and screenshot/history workflows before merging.

**Not yet device-certified:** this checklist is intentionally not marked complete until CI and
real-device tests pass.
