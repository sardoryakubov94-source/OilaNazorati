# Agora voice token service

The iOS and Android clients must never contain the Agora App Certificate. This Firebase
Function creates short-lived RTC tokens after verifying the signed-in family owner,
child device, current Firestore microphone request, and Premium entitlement.

## Configure and deploy

1. Use the Firebase project `oilanazorat-3c8de` and ensure it is on the Blaze plan
   (Cloud Functions deployment requires billing).
2. Set the Agora App Certificate as a Firebase Secret:

   ```sh
   firebase functions:secrets:set AGORA_APP_CERTIFICATE --project oilanazorat-3c8de
   ```

3. Deploy the function:

   ```sh
   firebase deploy --only functions:agoraVoiceToken,functions:agoraScreenToken --project oilanazorat-3c8de
   ```

4. Publish the Firestore rules that restrict the new child-owned consent document:

   ```sh
   firebase deploy --only firestore:rules --project oilanazorat-3c8de
   ```

5. When prompted for `AGORA_APP_ID`, enter the same Agora App ID used by the Android
   voice module. Do not put the App Certificate in the app, repository, or build secrets.

The iOS app calls:

`https://us-central1-oilanazorat-3c8de.cloudfunctions.net/agoraVoiceToken`

The function returns an RTC token and App ID only to a verified parent or the paired
child device. Parent subscriptions require the family's Premium entitlement.

## iOS behavior

The child must enable the in-app remote-audio consent once and grant the iOS microphone
permission once. Later parent requests are accepted automatically while the child app
is active. The child UI and iOS microphone indicator remain visible. The child can stop
the current session or revoke consent at any time. iOS suspends ordinary apps in the
background, so this implementation deliberately ends the audio session when the app
leaves the active state; it does not claim silent background microphone access.


## iOS screen sharing (ReplayKit)

The separate `agoraScreenToken` endpoint checks the paired iOS child, the child's
screen-share consent document, the active parent request, and Premium entitlement.
It issues publisher tokens only to the child and subscriber tokens only to the family
owner. The Broadcast Upload Extension is configured as a separate Xcode target and uses
Agora's ReplayKit extension product. iOS requires the child to manually confirm the
system Broadcast picker; a remote request never silently starts screen capture.

The parent screen viewer joins only after the child app advances the request to
`broadcast_ready`. End the broadcast from the iOS red recording indicator / Control Center
on the child device as well as stopping the remote Agora session in the parent panel.
