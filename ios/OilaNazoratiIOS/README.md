# OilaNazorati iOS — Screen Time companion

This folder is an isolated iOS implementation scaffold. Android files are untouched.

## Requirements
- Xcode 16+ / iOS 16+
- Add Firebase iOS SDK (FirebaseAuth, FirebaseFirestore) with Swift Package Manager.
- Register iOS bundle ID and configure Firebase: add the project's own `GoogleService-Info.plist` (never commit a production secrets/config file).
- Add Family Controls capability to the app **and** Device Activity Monitor extension targets.
- Request Apple's Family Controls distribution entitlement before TestFlight/App Store release.
- Family Sharing and guardian approval are required for child-device monitoring.

## Modules planned/started
- FamilyControls authorization + FamilyActivityPicker
- ManagedSettings app/category/domain shielding
- DeviceActivity schedule/event monitor
- Firestore parent-child status/report synchronization (only approved, privacy-permitted data)
- Parent dashboard reads the same Firestore document contract

## Important Apple privacy boundary
The picker returns opaque tokens; it does not disclose selected app names to the app. DeviceActivity reports and usage details are privacy-protected. This implementation must not promise unrestricted app-by-app history export. Raw app/site identity access requires Apple's separate App & Website Usage entitlement and explicit user authorization where available. Validate each feature on real Family Sharing devices and supported iOS versions.

## Data contract (Firestore)
`families/{familyId}/children/{childId}/iosStatus/current`
- `authorizationStatus`: string
- `lastSeenAt`: server timestamp
- `devicePlatform`: "ios"
- `locationSharingEnabled`: boolean (consent flag only; coordinates are not uploaded by this module)
- `limits`: configured policy summary
- `usageSummary`: only aggregates available through approved APIs/reports
