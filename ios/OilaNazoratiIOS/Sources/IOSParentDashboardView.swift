import SwiftUI
import FirebaseAuth
import FirebaseFirestore
import FirebaseCore
import GoogleSignIn
import UIKit

private enum ParentPalette {
    static let ink = Color(uiColor: .label)
    static let muted = Color(uiColor: .secondaryLabel)
    static let surface = Color(uiColor: .secondarySystemGroupedBackground)
    static let accent = Color(red: 0.25, green: 0.43, blue: 0.88)
    static let teal = Color(red: 0.12, green: 0.61, blue: 0.57)
    static let orange = Color(red: 0.91, green: 0.57, blue: 0.22)
    static let red = Color(red: 0.82, green: 0.28, blue: 0.31)
}

private struct IOSChildDevice: Identifiable, Hashable {
    let id: String
    let name: String
    let platform: DevicePlatform
    let capabilities: Set<MonitoringCapability>
    let screenTimeAuthorizationStatus: String
    let locationAuthorizationStatus: String
    let locationSharingConsent: Bool

    var isIOS: Bool { platform == .ios }
    var platformTitle: String { platform == .ios ? "iPhone / iPad" : platform == .android ? "Android" : "Aniqlanmagan" }
}

private struct IOSChildLocation {
    let latitude: Double
    let longitude: Double
    let timestamp: Date
}

private struct IOSAppUsage: Identifiable {
    let id: String
    let appName: String
    let durationSeconds: Int
}

struct IOSParentDashboardView: View {
    @AppStorage("device_role") private var deviceRoleRaw = DeviceRole.unselected.rawValue
    @State private var familyCode: String?
    @State private var children: [IOSChildDevice] = []
    @State private var selectedChildID: String?
    @State private var location: IOSChildLocation?
    @State private var androidAppUsage: [IOSAppUsage] = []
    @State private var focusEnabled = false
    @State private var loading = false
    @State private var signingIn = false
    @State private var errorMessage: String?
    @State private var infoMessage: String?
    @State private var selectedTab = 0
    @State private var childrenListener: ListenerRegistration?
    @State private var locationListener: ListenerRegistration?
    @State private var focusListener: ListenerRegistration?
    @State private var voiceListener: ListenerRegistration?
    @State private var micSettingsListener: ListenerRegistration?
    @State private var screenShareListener: ListenerRegistration?
    @State private var screenShareSettingsListener: ListenerRegistration?
    @State private var screenConsentEnabled = false
    @State private var screenShareStatus = "So‘rov yuborilmagan"
    @State private var screenShareRequestState = ""
    @State private var screenShareRequestID: String?
    @State private var screenShareChannel: String?
    @StateObject private var screenViewer = AgoraScreenViewer()
    @State private var voiceConsentEnabled = false
    @State private var voiceStatus = "Ulanmagan"
    @State private var voiceRequestState = ""
    @State private var voiceRequestID: String?
    @State private var voiceChannelName: String?
    @StateObject private var voiceSession = AgoraVoiceSession()
    @State private var boundChildID: String?
    @State private var boundChildPlatform: DevicePlatform?
    @State private var boundCapabilities: Set<MonitoringCapability>?
    @State private var boundFamilyCode: String?

    private var selectedChild: IOSChildDevice? {
        children.first { $0.id == selectedChildID }
    }

    var body: some View {
        Group {
            if Auth.auth().currentUser?.isAnonymous == false, familyCode != nil {
                dashboard
            } else {
                signInScreen
            }
        }
        .preferredColorScheme(nil)
        .onAppear(perform: restoreSession)
        .onDisappear(perform: removeListeners)
    }

    private var signInScreen: some View {
        ZStack {
            Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
            VStack(spacing: 22) {
                Spacer()
                ZStack {
                    RoundedRectangle(cornerRadius: 26, style: .continuous)
                        .fill(ParentPalette.accent.gradient)
                        .frame(width: 92, height: 92)
                    Image(systemName: "shield.lefthalf.filled")
                        .font(.system(size: 42, weight: .semibold))
                        .foregroundStyle(.white)
                }
                VStack(spacing: 8) {
                    Text("Oila Nazorati").font(.largeTitle.bold())
                    Text("Oilangiz xavfsizligi — bir joyda")
                        .font(.subheadline).foregroundStyle(ParentPalette.muted)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Label("Ota-ona hisobingiz bilan kiring", systemImage: "person.crop.circle")
                        .font(.headline)
                    Text("Farzand qurilmalarini ko‘rish uchun Android ilovasida oila kodini yaratgan Google hisobidan foydalaning.")
                        .font(.footnote).foregroundStyle(ParentPalette.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(18)
                .background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 18))
                if let errorMessage {
                    Text(errorMessage).font(.footnote).foregroundStyle(ParentPalette.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Button(action: signInWithGoogle) {
                    HStack(spacing: 10) {
                        if signingIn { ProgressView().tint(.white) }
                        else { Image(systemName: "g.circle.fill").font(.title3) }
                        Text(signingIn ? "Tekshirilmoqda…" : "Google orqali kirish").fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 16)
                    .foregroundStyle(.white)
                    .background(ParentPalette.accent, in: RoundedRectangle(cornerRadius: 15))
                }
                .disabled(signingIn)
                Text("Faqat oila kodining haqiqiy egasi bo‘lgan hisobga ruxsat beriladi.")
                    .font(.caption2).foregroundStyle(ParentPalette.muted)
                    .multilineTextAlignment(.center)
                Spacer()
                Text("Xavfsiz ulanish • Firebase himoyasi")
                    .font(.caption2).foregroundStyle(ParentPalette.muted)
            }
            .padding(24)
            .frame(maxWidth: 520)
        }
    }

    private var dashboard: some View {
        TabView(selection: $selectedTab) {
            NavigationStack { overview }
                .tabItem { Label("Bosh sahifa", systemImage: "square.grid.2x2.fill") }
                .tag(0)
            NavigationStack { devicesView }
                .tabItem { Label("Qurilmalar", systemImage: "iphone.gen3") }
                .tag(1)
            NavigationStack { settingsView }
                .tabItem { Label("Sozlamalar", systemImage: "gearshape.fill") }
                .tag(2)
        }
        .tint(ParentPalette.accent)
    }

    private var overview: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                if children.isEmpty {
                    emptyChildrenCard
                } else {
                    childSelector
                    if let child = selectedChild {
                        platformBanner(child)
                        locationCard
                        if child.platform == .android {
                            if child.capabilities.contains(.appUsage) {
                                androidMetrics
                            }
                            androidControls(child)
                            if child.capabilities.contains(.ambientAudio) {
                                voiceControls(child)
                            }
                        } else if child.platform == .ios {
                            iosControls(child)
                        } else {
                            unsupportedCard
                        }
                    }
                }
                if let infoMessage {
                    Label(infoMessage, systemImage: "checkmark.circle.fill")
                        .font(.footnote).foregroundStyle(ParentPalette.teal)
                }
                if let errorMessage {
                    Label(errorMessage, systemImage: "exclamationmark.circle.fill")
                        .font(.footnote).foregroundStyle(ParentPalette.red)
                }
            }
            .padding(18)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationBarHidden(true)
        .refreshable { refreshSelectedChild() }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Oila Nazorati").font(.title2.bold())
                Text("Ota-ona boshqaruv markazi").font(.caption).foregroundStyle(ParentPalette.muted)
            }
            Spacer()
            HStack(spacing: 6) {
                Circle().fill(ParentPalette.teal).frame(width: 7, height: 7)
                Text("Oila ulangan").font(.caption.weight(.medium))
            }
            .padding(.horizontal, 11).padding(.vertical, 8)
            .background(ParentPalette.teal.opacity(0.10), in: Capsule())
        }
        .padding(.top, 8)
    }

    private var childSelector: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeading("Farzand qurilmasi", detail: "\(children.count) ta ulangan")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(children) { child in
                        Button { select(child) } label: {
                            HStack(spacing: 10) {
                                Image(systemName: child.isIOS ? "iphone" : "smartphone")
                                    .font(.title3)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(child.name).font(.subheadline.weight(.semibold))
                                    Text(child.platformTitle).font(.caption2).opacity(0.75)
                                }
                            }
                            .foregroundStyle(selectedChildID == child.id ? .white : ParentPalette.ink)
                            .padding(.horizontal, 14).padding(.vertical, 12)
                            .background(selectedChildID == child.id ? ParentPalette.accent : ParentPalette.surface,
                                        in: RoundedRectangle(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func platformBanner(_ child: IOSChildDevice) -> some View {
        HStack(spacing: 12) {
            Image(systemName: child.isIOS ? "apple.logo" : "smartphone")
                .font(.title2)
                .foregroundStyle(child.isIOS ? ParentPalette.ink : ParentPalette.teal)
                .frame(width: 44, height: 44)
                .background(Color(uiColor: .tertiarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 13))
            VStack(alignment: .leading, spacing: 3) {
                Text(child.platformTitle).font(.subheadline.bold())
                Text(child.isIOS ? "Apple ruxsat bergan nazorat vositalari" : "Android nazorat modullari")
                    .font(.caption).foregroundStyle(ParentPalette.muted)
            }
            Spacer()
            Image(systemName: "checkmark.seal.fill").foregroundStyle(ParentPalette.teal)
        }
        .padding(14).background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 18))
    }

    private var locationCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeading("Joylashuv", detail: "Oxirgi yuborilgan ma’lumot")
            HStack(spacing: 12) {
                Image(systemName: "location.fill")
                    .font(.title2).foregroundStyle(ParentPalette.accent)
                    .frame(width: 46, height: 46)
                    .background(ParentPalette.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
                VStack(alignment: .leading, spacing: 4) {
                    if let location {
                        Text("\(location.latitude, specifier: "%.5f"), \(location.longitude, specifier: "%.5f")")
                            .font(.subheadline.monospaced().weight(.semibold))
                        Text(location.timestamp.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption).foregroundStyle(ParentPalette.muted)
                    } else {
                        Text("Joylashuv hali kelmagan").font(.subheadline.weight(.medium))
                        Text("Qurilma internet va joylashuv ruxsatini tekshiring")
                            .font(.caption).foregroundStyle(ParentPalette.muted)
                    }
                }
                Spacer(minLength: 0)
            }
            if let location {
                Button {
                    let url = URL(string: "https://maps.apple.com/?ll=\(location.latitude),\(location.longitude)")!
                    UIApplication.shared.open(url)
                } label: {
                    Label("Xaritada ochish", systemImage: "map")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(ParentPalette.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain).foregroundStyle(ParentPalette.accent)
            }
        }
        .padding(16).background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 20))
    }

    private var androidMetrics: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeading("Android ilovalari", detail: "Bugungi foydalanish vaqti")
            if androidAppUsage.isEmpty {
                Text("Bugun uchun ilova faolligi ma'lumoti topilmadi.")
                    .font(.caption).foregroundStyle(ParentPalette.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 15))
            } else {
                VStack(spacing: 0) {
                    ForEach(androidAppUsage) { item in
                        HStack(spacing: 12) {
                            Image(systemName: "app.fill")
                                .foregroundStyle(ParentPalette.accent)
                                .frame(width: 34, height: 34)
                                .background(ParentPalette.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                            Text(item.appName)
                                .font(.subheadline.weight(.medium))
                                .lineLimit(1)
                            Spacer(minLength: 8)
                            Text(formatUsageDuration(item.durationSeconds))
                                .font(.subheadline.monospacedDigit().weight(.semibold))
                                .foregroundStyle(ParentPalette.ink)
                        }
                        .padding(.vertical, 10)
                        if item.id != androidAppUsage.last?.id {
                            Divider()
                        }
                    }
                }
                .padding(.horizontal, 14)
                .background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 15))
            }
            Text("Faqat Android bolaning ilova faolligi ma'lumotlari ko'rsatiladi. SMS, qo‘ng‘iroq, kontakt va screenshot modullari iOS ota-ona interfeysida ochilmaydi.")
                .font(.caption2).foregroundStyle(ParentPalette.muted)
        }
    }

    private func androidControls(_ child: IOSChildDevice) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeading("Android boshqaruvi", detail: "Mavjud Android modullari")
            if child.capabilities.contains(.focusMode) {
                Toggle(isOn: Binding(
                    get: { focusEnabled },
                    set: { value in
                        focusEnabled = value
                        saveFocusMode(value, child: child)
                    }
                )) {
                    Label("Kontrol rejimi", systemImage: "hand.raised.fill")
                }
                .tint(ParentPalette.accent)
                Text("Yoqilganda Android bolaning qurilmasida ruxsat etilgan asosiy tizim ilovalaridan tashqari ilovalar Accessibility xizmati orqali cheklanadi.")
                    .font(.caption2).foregroundStyle(ParentPalette.muted)
            } else {
                Text("Bu Android qurilma Kontrol rejimini e'lon qilmagan.")
                    .font(.caption).foregroundStyle(ParentPalette.muted)
            }

        }
        .padding(16)
        .background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 18))
    }

    private func iosControls(_ child: IOSChildDevice) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeading("iOS nazorati", detail: "Apple Screen Time")
            Text("Quyidagi imkoniyatlar iOS’da qo‘llab-quvvatlanadi. Ishlashi uchun bola iPhone’ida Apple Family Controls ruxsati va kerakli tanlovlar bo‘lishi shart.")
                .font(.caption2).foregroundStyle(ParentPalette.muted)
            HStack {
                Label("Joylashuv ruxsati", systemImage: "location")
                Spacer()
                Text(locationPermissionLabel(child))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(child.locationSharingConsent && child.locationAuthorizationStatus.hasPrefix("authorized") ? ParentPalette.teal : ParentPalette.muted)
            }
            .font(.caption)
            if child.capabilities.contains(.focusMode) {
                HStack(spacing: 12) {
                    Image(systemName: "hourglass").font(.title2).foregroundStyle(ParentPalette.accent)
                        .frame(width: 42, height: 42).background(ParentPalette.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Umumiy Kontrol rejimi").font(.subheadline.bold())
                        Text("Eski Android/iOS mosligi uchun umumiy cheklov")
                            .font(.caption).foregroundStyle(ParentPalette.muted)
                    }
                    Spacer()
                    Toggle("Kontrol rejimi", isOn: Binding(
                        get: { focusEnabled },
                        set: { newValue in
                            focusEnabled = newValue
                            saveFocusMode(newValue, child: child)
                        }
                    ))
                    .labelsHidden().tint(ParentPalette.accent)
                }
                .padding(14).background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 16))
            }

            if child.capabilities.contains(.timeLimits) ||
                child.capabilities.contains(.schedules) ||
                child.capabilities.contains(.websiteRestrictions) {
                if let familyCode {
                    IOSScreenTimeParentControlsView(familyCode: familyCode, childUid: child.id)
                }
            } else {
                Text("Bu iPhone Screen Time boshqaruvini e'lon qilmagan. Bola ilovasini yangilash va iOS 16 yoki yangiroq versiyani tekshirish kerak.")
                    .font(.caption).foregroundStyle(ParentPalette.muted)
            }

            HStack {
                Label("Apple Screen Time ruxsati", systemImage: child.screenTimeAuthorizationStatus == "approved" ? "checkmark.shield.fill" : "lock.shield")
                    .font(.subheadline)
                Spacer()
                Text(child.screenTimeAuthorizationStatus == "approved" ? "Berilgan" : child.screenTimeAuthorizationStatus == "denied" ? "Rad etilgan" : "Kutilmoqda")
                    .font(.caption).foregroundStyle(child.screenTimeAuthorizationStatus == "approved" ? ParentPalette.teal : ParentPalette.orange)
            }
            .padding(12).background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 14))

            VStack(alignment: .leading, spacing: 9) {
                capabilityRow("Rozilik asosida joylashuv", available: child.capabilities.contains(.location))
                capabilityRow("Ilova/toifa statistikasi (bola qurilmasida)", available: child.capabilities.contains(.appUsage))
                capabilityRow("Kunlik/haftalik hisobot (bola qurilmasida)", available: child.capabilities.contains(.usageReports))
                capabilityRow("Kunlik vaqt limiti", available: child.capabilities.contains(.timeLimits))
                capabilityRow("Maktab va uyqu jadvali", available: child.capabilities.contains(.schedules))
                capabilityRow("Sayt cheklovlari", available: child.capabilities.contains(.websiteRestrictions))
            }
            .padding(14).background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 16))

            if child.capabilities.contains(.ambientAudio) {
                voiceControls(child)
            }
            screenShareControls(child)
            Text("iOS nazorati Apple Family Controls ruxsati, bola qurilmasidagi ilova tanlovi va Xcode extension sozlamalariga bog‘liq. SMS, qo‘ng‘iroq tarixi, kontaktlar, bildirishnoma matni, yashirin mikrofon va screenshot nazorati iOS’da ochiq API orqali berilmaydi.")
                .font(.caption2).foregroundStyle(ParentPalette.muted)
        }
    }


    private func screenShareControls(_ child: IOSChildDevice) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeading("Jonli ekran", detail: "ReplayKit • bolaning tasdig‘i bilan")
            HStack(spacing: 10) {
                Image(systemName: screenViewer.connected ? "rectangle.inset.filled" : "rectangle.on.rectangle")
                    .font(.title2).foregroundStyle(screenViewer.connected ? ParentPalette.teal : ParentPalette.accent)
                    .frame(width: 42, height: 42).background(ParentPalette.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 3) {
                    Text(screenViewer.connected ? "Ekran ulandi" : "Ekran holati").font(.subheadline.bold())
                    Text(screenShareStatus).font(.caption).foregroundStyle(ParentPalette.muted)
                }
                Spacer()
            }
            if screenViewer.connected {
                AgoraScreenVideoSurface(viewer: screenViewer)
                    .frame(height: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                Button(role: .destructive, action: stopRemoteScreenShare) {
                    Label("Ekran ulashishni to‘xtatish", systemImage: "stop.fill").frame(maxWidth: .infinity)
                }.buttonStyle(.bordered)
            } else {
                Label(screenConsentEnabled ? "Bola oldindan rozilik bergan" : "Bola qurilmasida ekran roziligi kerak",
                      systemImage: screenConsentEnabled ? "checkmark.shield.fill" : "lock.shield")
                    .font(.caption).foregroundStyle(screenConsentEnabled ? ParentPalette.teal : ParentPalette.orange)
                Button(action: { requestRemoteScreenShare(child) }) {
                    Label("Ekranni jonli ko‘rish", systemImage: "rectangle.on.rectangle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(ParentPalette.accent)
                .disabled(!screenConsentEnabled || ["requested", "broadcast_ready", "active"].contains(screenShareRequestState))
            }
            Text("iOS’da ota-ona so‘rovi uzatishni yashirin boshlamaydi. Bola ilovasida so‘rovni ko‘rib, Broadcast’ni o‘zi tasdiqlaydi; qizil iOS indikatori ko‘rinadi.")
                .font(.caption2).foregroundStyle(ParentPalette.muted)
        }
        .padding(16).background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 18))
    }

    private func requestRemoteScreenShare(_ child: IOSChildDevice) {
        // Agora iOS engine is shared: stop any voice session before opening screen video.
        if voiceSession.isConnected || voiceRequestState == "requested" || voiceRequestState == "active" {
            stopRemoteVoice()
        }
        guard let code = familyCode, let ownerUID = Auth.auth().currentUser?.uid else {
            errorMessage = "Ota-ona hisobi aniqlanmadi."; return
        }
        let requestID = UUID().uuidString
        let channel = "scr_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased())"
        screenShareRequestID = requestID
        screenShareChannel = channel
        screenShareRequestState = "requested"
        screenShareStatus = "Bola qurilmasiga ekran so‘rovi yuborildi"
        let ref = Firestore.firestore().collection("families").document(code)
            .collection("children").document(child.id).collection("screen_share_requests").document("current")
        ref.setData([
            "requestId": requestID, "channelName": channel, "status": "requested",
            "requestedByUid": ownerUID, "platform": "ios",
            "createdAtMs": Int64(Date().timeIntervalSince1970 * 1000),
            "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000),
            "expiresAtMs": Int64(Date().timeIntervalSince1970 * 1000) + 10 * 60 * 1000
        ]) { error in
            DispatchQueue.main.async {
                if let error { errorMessage = "Ekran so‘rovi yuborilmadi: \(error.localizedDescription)" }
                else { infoMessage = "Ekran so‘rovi yuborildi. Bola qurilmasida tasdiqlanishini kuting." }
            }
        }
    }

    private func stopRemoteScreenShare() {
        guard let code = familyCode, let child = selectedChild else { return }
        sendScreenStopRequest(to: child.id)
        screenViewer.leave()
        screenShareStatus = "Ekran uzatish to‘xtatilmoqda…"
    }

    private func voiceControls(_ child: IOSChildDevice) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeading("Jonli ovoz", detail: "Bolaning oldindan bergan roziligi asosida")
            HStack(spacing: 10) {
                Image(systemName: voiceSession.isConnected ? "waveform" : "mic")
                    .font(.title2)
                    .foregroundStyle(voiceSession.isConnected ? ParentPalette.teal : ParentPalette.accent)
                    .frame(width: 42, height: 42)
                    .background(ParentPalette.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 3) {
                    Text(voiceSession.isConnected ? "Ovoz ulandi" : "Ovoz holati")
                        .font(.subheadline.bold())
                    Text(voiceStatus)
                        .font(.caption).foregroundStyle(ParentPalette.muted)
                }
                Spacer()
                if voiceSession.isConnected {
                    Circle().fill(ParentPalette.teal).frame(width: 9, height: 9)
                }
            }
            if child.platform == .ios {
                Label(voiceConsentEnabled ? "Bola jonli ovozga rozilik bergan" : "Bola qurilmasida bir martalik rozilik kerak",
                      systemImage: voiceConsentEnabled ? "checkmark.shield.fill" : "lock.shield")
                    .font(.caption)
                    .foregroundStyle(voiceConsentEnabled ? ParentPalette.teal : ParentPalette.orange)
            } else {
                Label("Android mikrofon ruxsati va faol xizmat talab qilinadi", systemImage: "mic.badge.checkmark")
                    .font(.caption)
                    .foregroundStyle(ParentPalette.teal)
            }
            if voiceRequestState == "requested" || voiceRequestState == "active" || voiceSession.isConnected {
                Button(role: .destructive, action: stopRemoteVoice) {
                    Label("Ovoz ulanishini to‘xtatish", systemImage: "stop.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            } else {
                Button(action: { requestRemoteVoice(child) }) {
                    Label("Jonli ovozni eshitish", systemImage: "headphones")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(ParentPalette.accent)
                .disabled(!voiceConsentEnabled)
            }
            if child.platform == .ios {
                Text("iOS’da bola bir marta rozilik beradi. Keyin ota-ona so‘rovi ilova faol va ochiq turganida avtomatik qabul qilinadi. Mikrofon indikatori ko‘rinadi; ilova fonda to‘xtasa, sessiya ham tugaydi.")
                    .font(.caption2).foregroundStyle(ParentPalette.muted)
            } else {
                Text("Android’da mavjud Agora xizmati ishlatiladi. Bola qurilmasida mikrofon ruxsati va tizimning faol xizmat bildirishnomasi saqlanadi.")
                    .font(.caption2).foregroundStyle(ParentPalette.muted)
            }
        }
        .padding(16)
        .background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 18))
    }

    private func requestRemoteVoice(_ child: IOSChildDevice) {
        guard let code = familyCode, let uid = Auth.auth().currentUser?.uid else {
            errorMessage = "Ota-ona hisobi aniqlanmadi."
            return
        }
        let requestID = UUID().uuidString
        let channel = "on_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased())"
        voiceRequestID = requestID
        voiceChannelName = channel
        voiceRequestState = "requested"
        voiceStatus = "Bola qurilmasiga so‘rov yuborildi"
        let request: [String: Any] = [
            "requestId": requestID,
            "channelName": channel,
            "status": "requested",
            "requestedByUid": uid,
            "requestedAtMs": Int64(Date().timeIntervalSince1970 * 1000),
            "expiresAtMs": Int64(Date().addingTimeInterval(90).timeIntervalSince1970 * 1000),
            "platform": child.platform.rawValue,
            "transport": "agora"
        ]
        Firestore.firestore().collection("families").document(code)
            .collection("children").document(child.id)
            .collection("mic_requests").document("current")
            .setData(request, merge: true) { error in
                DispatchQueue.main.async {
                    if let error {
                        voiceStatus = "Ovoz so‘rovini yuborib bo‘lmadi"
                        let nsError = error as NSError
                        errorMessage = nsError.code == FirestoreErrorCode.permissionDenied.rawValue
                            ? "Jonli ovoz Premium obunasi bilan ishlaydi."
                            : error.localizedDescription
                    } else {
                        infoMessage = "Ovoz so‘rovi yuborildi. Bola qurilmasi faol bo‘lishi kerak."
                    }
                }
            }
    }

    private func stopRemoteVoice() {
        guard let code = familyCode, let child = selectedChild else {
            voiceSession.leave()
            return
        }
        voiceSession.leave()
        voiceStatus = "To‘xtatilmoqda…"
        Firestore.firestore().collection("families").document(code)
            .collection("children").document(child.id)
            .collection("mic_requests").document("current")
            .updateData([
                "status": "stop_requested",
                "stoppedAtMs": Int64(Date().timeIntervalSince1970 * 1000)
            ]) { error in
                DispatchQueue.main.async {
                    voiceStatus = error == nil ? "Ulanish to‘xtatildi" : "To‘xtatish holati yuborilmadi"
                    if let error { errorMessage = error.localizedDescription }
                }
            }
    }

    private var unsupportedCard: some View {
        Label("Qurilma platformasi aniqlanmadi. Xavfsizlik uchun nazorat bo‘limlari yopiq.", systemImage: "exclamationmark.triangle.fill")
            .font(.footnote).foregroundStyle(ParentPalette.orange)
            .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(ParentPalette.orange.opacity(0.10), in: RoundedRectangle(cornerRadius: 16))
    }

    private var emptyChildrenCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "iphone.and.arrow.forward").font(.system(size: 36)).foregroundStyle(ParentPalette.accent)
            Text("Hali qurilma ulanmagan").font(.headline)
            Text("Android ota-ona ilovasida oila kodi yarating va farzand telefonini shu oilaga ulang. Bu sahifa o‘sha Google hisobiga tegishli qurilmalarni avtomatik topadi.")
                .font(.footnote).foregroundStyle(ParentPalette.muted).multilineTextAlignment(.center)
            Button("Qayta yuklash") { loadFamilyForCurrentUser() }
                .buttonStyle(.borderedProminent).tint(ParentPalette.accent)
        }
        .padding(22).frame(maxWidth: .infinity)
        .background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 20))
    }

    private var devicesView: some View {
        List {
            Section("Ulangan qurilmalar") {
                ForEach(children) { child in
                    Button { select(child); selectedTab = 0 } label: {
                        HStack(spacing: 12) {
                            Image(systemName: child.isIOS ? "iphone" : "smartphone")
                                .font(.title2).foregroundStyle(ParentPalette.accent)
                                .frame(width: 42, height: 42)
                                .background(ParentPalette.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(child.name).font(.subheadline.weight(.semibold)).foregroundStyle(ParentPalette.ink)
                                Text(child.platformTitle).font(.caption).foregroundStyle(ParentPalette.muted)
                            }
                            Spacer()
                            if selectedChildID == child.id { Image(systemName: "checkmark.circle.fill").foregroundStyle(ParentPalette.accent) }
                        }
                    }
                }
            }
            Section {
                Text("Qurilma platformasi Firestore’dagi childPlatform maydonidan aniqlanadi. Aniqlanmagan qurilmaga hech qanday nazorat imkoniyati avtomatik berilmaydi.")
                    .font(.footnote).foregroundStyle(ParentPalette.muted)
            }
        }
        .navigationTitle("Qurilmalar")
        .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button(action: loadFamilyForCurrentUser) { Image(systemName: "arrow.clockwise") } } }
    }

    private var settingsView: some View {
        List {
            Section("Hisob") {
                LabeledContent("Google hisob", value: Auth.auth().currentUser?.email ?? "—")
                LabeledContent("Oila kodi", value: familyCode ?? "—")
            }
            Section("Xavfsizlik") {
                Label("Faqat oila egasi", systemImage: "checkmark.shield")
                Label("Platformaga mos imkoniyatlar", systemImage: "slider.horizontal.3")
                Label("Noma’lum platformada yopiq rejim", systemImage: "lock")
            }
            Section {
                Button(role: .destructive) { signOut() } label: {
                    Label("Hisobdan chiqish", systemImage: "rectangle.portrait.and.arrow.right")
                }
                Button { deviceRoleRaw = DeviceRole.unselected.rawValue } label: {
                    Label("Qurilma rolini qayta tanlash", systemImage: "arrow.uturn.backward")
                }
            }
        }
        .navigationTitle("Sozlamalar")
    }

    private func sectionHeading(_ title: String, detail: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.headline)
            Spacer()
            Text(detail).font(.caption).foregroundStyle(ParentPalette.muted)
        }
    }

    private func capabilityRow(_ title: String, available: Bool) -> some View {
        HStack {
            Image(systemName: available ? "checkmark.circle.fill" : "minus.circle")
                .foregroundStyle(available ? ParentPalette.teal : ParentPalette.muted)
            Text(title).font(.subheadline)
            Spacer()
            Text(available ? "Qo‘llab-quvvatlanadi" : "Mavjud emas")
                .font(.caption).foregroundStyle(ParentPalette.muted)
        }
    }

    private func restoreSession() {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return }
        loadFamilyForCurrentUser()
    }

    private func signInWithGoogle() {
        guard let clientID = FirebaseApp.app()?.options.clientID,
              let controller = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows })
                .first(where: { $0.isKeyWindow })?.rootViewController else {
            errorMessage = "Google/Firebase sozlamalari topilmadi. GoogleService-Info.plist faylini tekshiring."
            return
        }
        signingIn = true
        errorMessage = nil
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        GIDSignIn.sharedInstance.signIn(withPresenting: controller) { result, error in
            if let error {
                DispatchQueue.main.async {
                    signingIn = false
                    errorMessage = "Google orqali kirib bo‘lmadi: \(error.localizedDescription)"
                }
                return
            }
            guard let idToken = result?.user.idToken?.tokenString else {
                DispatchQueue.main.async {
                    signingIn = false
                    errorMessage = "Google ID token olinmadi."
                }
                return
            }
            let credential = GoogleAuthProvider.credential(withIDToken: idToken,
                                                            accessToken: result?.user.accessToken.tokenString ?? "")
            Auth.auth().signIn(with: credential) { authResult, authError in
                DispatchQueue.main.async {
                    signingIn = false
                    if let authError {
                        errorMessage = "Firebase kirish xatosi: \(authError.localizedDescription)"
                    } else if authResult?.user.isAnonymous == true {
                        errorMessage = "Ota-ona uchun Google hisobi talab qilinadi."
                    } else {
                        loadFamilyForCurrentUser()
                    }
                }
            }
        }
    }

    private func loadFamilyForCurrentUser() {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else {
            errorMessage = "Avval ota-ona Google hisobi bilan kiring."
            return
        }
        loading = true
        Firestore.firestore().collection("families")
            .whereField("ownerUid", isEqualTo: user.uid)
            .limit(to: 1).getDocuments { snapshot, error in
                DispatchQueue.main.async {
                    loading = false
                    if let error {
                        errorMessage = "Oila ma’lumotini yuklab bo‘lmadi: \(error.localizedDescription)"
                        return
                    }
                    guard let document = snapshot?.documents.first else {
                        familyCode = nil
                        errorMessage = "Bu Google hisobiga tegishli oila kodi topilmadi. Android ilovasida aynan shu hisob bilan oila yaratilganini tekshiring."
                        return
                    }
                    familyCode = document.documentID
                    listenToChildren(code: document.documentID)
                }
            }
    }

    private func listenToChildren(code: String) {
        childrenListener?.remove()
        childrenListener = Firestore.firestore().collection("families").document(code)
            .collection("children").addSnapshotListener { snapshot, error in
                DispatchQueue.main.async {
                    if let error {
                        errorMessage = "Qurilmalar ro‘yxati yuklanmadi: \(error.localizedDescription)"
                        return
                    }
                    let loaded = (snapshot?.documents ?? []).map { doc -> IOSChildDevice in
                        let data = doc.data()
                        let platform = ChildPlatformResolver.resolve(from: data)
                        let declared = (data["supportedCapabilities"] as? [String]).flatMap { values in
                            Set(values.compactMap(MonitoringCapability.init(rawValue:)))
                        }
                        // Android child already ships the Agora microphone service.
                        // Older Android metadata may omit this capability, so retain
                        // the known existing voice support without widening other features.
                        let effectiveDeclared = platform == .android
                            ? (declared ?? DeviceCapabilityMatrix.capabilities(for: .android)).union([.ambientAudio])
                            : declared
                        // Child iOS versiyasi ota-ona iPhone versiyasidan mustaqil.
                        // Eski iOS bola ilovasi capability yubormasa, faqat xavfsiz
                        // joylashuv imkoniyatini ko'rsatamiz; Screen Time'ni taxmin qilmaymiz.
                        let capabilities = CrossPlatformConfiguration.resolve(
                            parentPlatform: .ios,
                            childPlatform: platform,
                            declaredCapabilities: effectiveDeclared
                        ).capabilities
                        return IOSChildDevice(
                            id: doc.documentID,
                            name: (data["nomi"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Farzand",
                            platform: platform,
                            capabilities: capabilities,
                            screenTimeAuthorizationStatus: data["screenTimeAuthorizationStatus"] as? String ?? "notDetermined",
                            locationAuthorizationStatus: data["locationAuthorizationStatus"] as? String ?? "unknown",
                            locationSharingConsent: data["locationSharingConsent"] as? Bool ?? false
                        )
                    }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                    if !loaded.contains(where: { $0.id == selectedChildID }) {
                        selectedChildID = loaded.first?.id
                    }
                    children = loaded
                    if let child = selectedChild {
                        let needsRebind = boundChildID != child.id ||
                            boundChildPlatform != child.platform ||
                            boundCapabilities != child.capabilities ||
                            boundFamilyCode != code
                        if needsRebind { bindSelectedChild(child) }
                    } else {
                        if let oldChildID = boundChildID,
                           voiceRequestState == "requested" || voiceRequestState == "active" {
                            sendVoiceStopRequest(to: oldChildID)
                        }
                        locationListener?.remove()
                        focusListener?.remove()
                        voiceListener?.remove()
                        micSettingsListener?.remove()
                        voiceConsentEnabled = false
                        voiceSession.leave()
                        voiceStatus = "Ulanmagan"
                        voiceRequestState = ""
                        voiceRequestID = nil
                        voiceChannelName = nil
                        location = nil
                        androidAppUsage = []
                        boundChildID = nil
                        boundChildPlatform = nil
                        boundCapabilities = nil
                        boundFamilyCode = nil
                    }
                }
            }
    }

    private func select(_ child: IOSChildDevice) {
        if let oldChildID = boundChildID, oldChildID != child.id {
            if voiceRequestState == "requested" || voiceRequestState == "active" { sendVoiceStopRequest(to: oldChildID) }
            if ["requested", "broadcast_ready", "active"].contains(screenShareRequestState) { sendScreenStopRequest(to: oldChildID) }
        }
        selectedChildID = child.id
        bindSelectedChild(child)
    }

    private func sendVoiceStopRequest(to childID: String) {
        guard let code = familyCode else { return }
        Firestore.firestore().collection("families").document(code)
            .collection("children").document(childID)
            .collection("mic_requests").document("current")
            .updateData([
                "status": "stop_requested",
                "stoppedAtMs": Int64(Date().timeIntervalSince1970 * 1000)
            ])
    }

    private func sendScreenStopRequest(to childID: String) {
        guard let code = familyCode else { return }
        Firestore.firestore().collection("families").document(code)
            .collection("children").document(childID)
            .collection("screen_share_requests").document("current")
            .updateData(["status": "stop_requested", "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000)])
    }

    private func bindSelectedChild(_ child: IOSChildDevice) {
        boundChildID = child.id
        boundChildPlatform = child.platform
        boundCapabilities = child.capabilities
        boundFamilyCode = familyCode
        locationListener?.remove()
        focusListener?.remove()
        voiceListener?.remove()
        micSettingsListener?.remove()
        screenShareListener?.remove()
        screenShareSettingsListener?.remove()
        screenViewer.leave()
        screenConsentEnabled = false
        screenShareRequestState = ""
        screenShareRequestID = nil
        screenShareChannel = nil
        screenShareStatus = "So‘rov yuborilmagan"
        voiceConsentEnabled = false
        voiceSession.leave()
        voiceStatus = "Ulanmagan"
        voiceRequestState = ""
        voiceRequestID = nil
        voiceChannelName = nil
        location = nil
        metrics = []
        guard let code = familyCode else { return }
        let childRef = Firestore.firestore().collection("families").document(code)
            .collection("children").document(child.id)

        if child.capabilities.contains(.location) {
            locationListener = childRef.collection("locations")
                .order(by: "vaqtMs", descending: true).limit(to: 1)
                .addSnapshotListener { snapshot, _ in
                    guard let data = snapshot?.documents.first?.data(),
                          let lat = data["lat"] as? Double,
                          let lng = data["lng"] as? Double else { return }
                    let rawTime = data["vaqtMs"] as? Double ?? (data["vaqtMs"] as? Int).map(Double.init) ?? 0
                    DispatchQueue.main.async {
                        location = IOSChildLocation(latitude: lat, longitude: lng,
                                                     timestamp: Date(timeIntervalSince1970: rawTime > 10_000_000_000 ? rawTime / 1000 : rawTime))
                    }
                }
        }
        if child.capabilities.contains(.focusMode) {
            focusListener = childRef.collection("focus_mode_settings").document("current")
                .addSnapshotListener { snapshot, _ in
                    DispatchQueue.main.async { focusEnabled = snapshot?.data()?["enabled"] as? Bool ?? false }
                }
        }
        if child.platform == .android, child.capabilities.contains(.ambientAudio) {
            voiceConsentEnabled = true
        }
        if child.platform == .ios, child.capabilities.contains(.ambientAudio) {
            micSettingsListener = childRef.collection("mic_settings").document("current")
                .addSnapshotListener { snapshot, _ in
                    DispatchQueue.main.async {
                        voiceConsentEnabled = snapshot?.data()?["remoteAudioConsent"] as? Bool ?? false
                    }
                }
        }
        if child.platform == .ios {
            screenShareSettingsListener = childRef.collection("screen_share_settings").document("current")
                .addSnapshotListener { snapshot, _ in
                    DispatchQueue.main.async { screenConsentEnabled = snapshot?.data()?["screenShareConsent"] as? Bool ?? false }
                }
            screenShareListener = childRef.collection("screen_share_requests").document("current")
                .addSnapshotListener { snapshot, error in
                    DispatchQueue.main.async {
                        if let error { screenShareStatus = error.localizedDescription; return }
                        guard let data = snapshot?.data(), let status = data["status"] as? String else {
                            screenShareStatus = "Bola ekran so‘rovini kutmoqda"; return
                        }
                        screenShareRequestState = status
                        let rid = data["requestId"] as? String
                        let channel = data["channelName"] as? String
                        switch status {
                        case "requested": screenShareStatus = "Bola qurilmasiga so‘rov yuborildi"
                        case "broadcast_ready", "active":
                            screenShareStatus = "Bola Broadcast’ni boshladi"
                            if let rid, let channel, screenShareRequestID == rid, !screenViewer.connected {
                                screenViewer.join(channel: channel, familyCode: code, childUid: child.id, requestID: rid)
                            }
                        case "stop_requested", "stopped", "failed", "expired", "cancelled":
                            screenViewer.leave()
                            screenShareStatus = status == "stopped" ? "Ekran uzatish tugadi" : "Ekran ulashish: \(status)"
                        default: screenShareStatus = status
                        }
                    }
                }
        }
        if child.capabilities.contains(.ambientAudio) {
            voiceListener = childRef.collection("mic_requests").document("current")
                .addSnapshotListener { snapshot, error in
                    DispatchQueue.main.async {
                        if let error {
                            voiceStatus = "Ovoz holatini o‘qib bo‘lmadi"
                            errorMessage = error.localizedDescription
                            return
                        }
                        guard let data = snapshot?.data(),
                              let status = data["status"] as? String else {
                            voiceStatus = "Bola ruxsatini kutmoqda"
                            return
                        }
                        voiceRequestState = status
                        switch status {
                        case "requested": voiceStatus = "Bola qurilmasiga so‘rov yuborildi"
                        case "active": voiceStatus = "Jonli ovoz faol"
                        case "stop_requested": voiceStatus = "Ovoz uzatish to‘xtatilmoqda…"
                        case "stopped": voiceStatus = "Ovoz uzatish to‘xtatildi"
                        case "consent_not_enabled": voiceStatus = "Bola qurilmasida oldindan rozilik yoqilmagan"
                        case "failed": voiceStatus = "Ovoz ulanishida xatolik"
                        case "expired": voiceStatus = "So‘rov muddati tugadi"
                        default: voiceStatus = status
                        }
                        let requestID = data["requestId"] as? String
                        let channel = data["channelName"] as? String
                        if status == "active", let requestID, let channel,
                           voiceRequestID == requestID, !voiceSession.isConnected, !voiceSession.isConnecting {
                            voiceSession.join(channel: channel, role: "subscriber", familyCode: familyCode ?? "", childUid: child.id, requestID: requestID) { result in
                                if case .failure(let error) = result {
                                    voiceStatus = error.localizedDescription
                                } else {
                                    voiceStatus = "Jonli ovoz ulandi"
                                }
                            }
                        } else if ["stop_requested", "stopped", "cancelled", "expired", "failed", "consent_not_enabled"].contains(status) {
                            voiceSession.leave()
                            if status == "consent_not_enabled" {
                                voiceStatus = "Bola qurilmasida ovoz roziligi yoqilmagan"
                            }
                        }
                    }
                }
        }
        if child.platform == .android, child.capabilities.contains(.appUsage) {
            loadAndroidMetrics(childRef)
        }
    }

    private func loadAndroidMetrics(_ childRef: DocumentReference) {
        let calendar = Calendar.current
        let startOfDay = calendar.startOfDay(for: Date()).timeIntervalSince1970 * 1000
        let endOfDay = startOfDay + 24 * 60 * 60 * 1000
        childRef.collection("app_usage")
            .whereField("boshlanishMs", isGreaterThanOrEqualTo: startOfDay)
            .whereField("boshlanishMs", isLessThan: endOfDay)
            .order(by: "boshlanishMs", descending: true)
            .getDocuments { snapshot, _ in
                let grouped = Dictionary(grouping: snapshot?.documents ?? [], by: {
                    $0.data()["ilovaNomi"] as? String ?? "Noma'lum ilova"
                })
                let usage = grouped.map { entry in
                    let totalSeconds = entry.value.reduce(0) { total, document in
                        let seconds = (document.data()["davomiylikSoniya"] as? NSNumber)?.intValue ?? 0
                        return total + max(0, seconds)
                    }
                    return IOSAppUsage(
                        id: entry.key,
                        appName: entry.key,
                        durationSeconds: totalSeconds
                    )
                }.sorted { $0.durationSeconds > $1.durationSeconds }
                DispatchQueue.main.async {
                    guard selectedChildID == childRef.documentID else { return }
                    androidAppUsage = usage
                }
            }
    }

    private func formatUsageDuration(_ seconds: Int) -> String {
        let safeSeconds = max(0, seconds)
        let hours = safeSeconds / 3600
        let minutes = (safeSeconds % 3600) / 60
        return hours > 0 ? "\(hours) soat \(minutes) daq" : "\(minutes) daq"
    }

    private func locationPermissionLabel(_ child: IOSChildDevice) -> String {
        guard child.locationSharingConsent else { return "Rozilik berilmagan" }
        switch child.locationAuthorizationStatus {
        case "authorizedAlways": return "Doimiy ruxsat"
        case "authorizedWhenInUse": return "Ilova ishlaganda"
        case "denied": return "Rad etilgan"
        case "restricted": return "Cheklangan"
        case "notDetermined": return "So'ralmagan"
        default: return "Noma'lum"
        }
    }

    private func saveFocusMode(_ enabled: Bool, child: IOSChildDevice) {
        guard child.capabilities.contains(.focusMode), let code = familyCode,
              let uid = Auth.auth().currentUser?.uid else {
            infoMessage = "Bu qurilmada ushbu boshqaruv mavjud emas."
            return
        }
        let ref = Firestore.firestore().collection("families").document(code)
            .collection("children").document(child.id)
            .collection("focus_mode_settings").document("current")
        ref.setData([
            "enabled": enabled,
            "allowedPackages": [],
            "updatedAt": Int64(Date().timeIntervalSince1970 * 1000),
            "updatedByUid": uid
        ], merge: true) { error in
            DispatchQueue.main.async {
                if let error {
                    focusEnabled.toggle()
                    errorMessage = "Sozlama yuborilmadi: \(error.localizedDescription)"
                } else {
                    infoMessage = enabled ? "Kontrol rejimi sozlamasi yuborildi." : "Kontrol rejimi o‘chirildi."
                }
            }
        }
    }

    private func refreshSelectedChild() {
        guard let child = selectedChild else { loadFamilyForCurrentUser(); return }
        bindSelectedChild(child)
    }

    private func removeListeners() {
        if let childID = boundChildID {
            if voiceRequestState == "requested" || voiceRequestState == "active" { sendVoiceStopRequest(to: childID) }
            if ["requested", "broadcast_ready", "active"].contains(screenShareRequestState) { sendScreenStopRequest(to: childID) }
        }
        childrenListener?.remove()
        locationListener?.remove()
        focusListener?.remove()
        voiceListener?.remove()
        micSettingsListener?.remove()
        voiceSession.leave()
        screenViewer.leave()
        boundChildID = nil
        boundChildPlatform = nil
        boundCapabilities = nil
        boundFamilyCode = nil
    }

    private func signOut() {
        removeListeners()
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
        familyCode = nil
        children = []
        selectedChildID = nil
        location = nil
        metrics = []
        errorMessage = nil
    }
}
