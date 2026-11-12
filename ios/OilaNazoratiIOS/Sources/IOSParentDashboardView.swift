import SwiftUI
import Charts
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

private struct IOSMetric: Identifiable {
    let id: String
    let title: String
    let value: Int
    let symbol: String
    let tint: Color
}

struct IOSParentDashboardView: View {
    @AppStorage("device_role") private var deviceRoleRaw = DeviceRole.unselected.rawValue
    @State private var familyCode: String?
    @State private var children: [IOSChildDevice] = []
    @State private var selectedChildID: String?
    @State private var location: IOSChildLocation?
    @State private var metrics: [IOSMetric] = []
    @State private var focusEnabled = false
    @State private var screenshotRequestStatus = "Hali so'rov yuborilmagan"
    @State private var latestAndroidScreenshot: UIImage?
    @State private var loading = false
    @State private var signingIn = false
    @State private var errorMessage: String?
    @State private var infoMessage: String?
    @State private var selectedTab = 0
    @State private var childrenListener: ListenerRegistration?
    @State private var locationListener: ListenerRegistration?
    @State private var focusListener: ListenerRegistration?
    @State private var screenshotListener: ListenerRegistration?
    @State private var latestScreenshotListener: ListenerRegistration?
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
                            androidMetrics
                            androidControls(child)
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
            sectionHeading("Android faoliyati", detail: "Bugungi ma’lumotlar")
            if !metrics.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Faollik taqsimoti").font(.subheadline.weight(.semibold))
                    Chart(metrics) { metric in
                        BarMark(
                            x: .value("Yozuvlar", metric.value),
                            y: .value("Bo‘lim", metric.title)
                        )
                        .foregroundStyle(metric.tint.gradient)
                        .cornerRadius(5)
                    }
                    .chartXAxis { AxisMarks(position: .bottom) }
                    .chartYAxis { AxisMarks(position: .leading) }
                    .frame(height: 190)
                    .accessibilityLabel("Android faoliyati bo‘yicha kunlik ko‘rsatkichlar")
                }
                .padding(14)
                .background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 17))
            }
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(metrics) { metric in
                    HStack(spacing: 10) {
                        Image(systemName: metric.symbol).font(.title3)
                            .foregroundStyle(metric.tint).frame(width: 34, height: 34)
                            .background(metric.tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(metric.value.formatted()).font(.title3.bold().monospacedDigit())
                            Text(metric.title).font(.caption).foregroundStyle(ParentPalette.muted).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(12).background(ParentPalette.surface, in: RoundedRectangle(cornerRadius: 15))
                }
            }
            Text("Ko‘rsatkichlar Firestore’da mavjud bo‘lgan Android yozuvlari asosida olinadi. Bu iOS ilovasiga Android qurilmasining barcha tizim ruxsatlarini bermaydi.")
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

            if child.capabilities.contains(.screenshots) {
                Button {
                    requestAndroidScreenshot(child)
                } label: {
                    Label("Ekran rasmini so'rash", systemImage: "camera.viewfinder")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity).padding(.vertical, 11)
                        .background(ParentPalette.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .foregroundStyle(ParentPalette.accent)
                Text(screenshotRequestStatus)
                    .font(.caption).foregroundStyle(ParentPalette.muted)
                if let latestAndroidScreenshot {
                    Image(uiImage: latestAndroidScreenshot)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel("Android bolaning so'nggi screenshot rasmi")
                }
                Text("Screenshot olish uchun bolaning Android qurilmasida Accessibility va screenshot ruxsatlari hamda screenshot moduli yoqilgan bo'lishi kerak.")
                    .font(.caption2).foregroundStyle(ParentPalette.muted)
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

            Text("iOS nazorati Apple Family Controls ruxsati, bola qurilmasidagi ilova tanlovi va Xcode extension sozlamalariga bog‘liq. SMS, qo‘ng‘iroq tarixi, kontaktlar, bildirishnoma matni, yashirin mikrofon va screenshot nazorati iOS’da ochiq API orqali berilmaydi.")
                .font(.caption2).foregroundStyle(ParentPalette.muted)
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
                        // Child iOS versiyasi ota-ona iPhone versiyasidan mustaqil.
                        // Eski iOS bola ilovasi capability yubormasa, faqat xavfsiz
                        // joylashuv imkoniyatini ko'rsatamiz; Screen Time'ni taxmin qilmaymiz.
                        let capabilities = declared ?? (platform == .ios
                            ? Set<MonitoringCapability>([.location])
                            : DeviceCapabilityMatrix.capabilities(for: platform))
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
                        locationListener?.remove()
                        focusListener?.remove()
                        screenshotListener?.remove()
                        latestScreenshotListener?.remove()
                        location = nil
                        metrics = []
                        latestAndroidScreenshot = nil
                        boundChildID = nil
                        boundChildPlatform = nil
                        boundCapabilities = nil
                        boundFamilyCode = nil
                    }
                }
            }
    }

    private func select(_ child: IOSChildDevice) {
        selectedChildID = child.id
        bindSelectedChild(child)
    }

    private func bindSelectedChild(_ child: IOSChildDevice) {
        boundChildID = child.id
        boundChildPlatform = child.platform
        boundCapabilities = child.capabilities
        boundFamilyCode = familyCode
        locationListener?.remove()
        focusListener?.remove()
        screenshotListener?.remove()
        latestScreenshotListener?.remove()
        location = nil
        metrics = []
        latestAndroidScreenshot = nil
        screenshotRequestStatus = "Hali so'rov yuborilmagan"
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
        if child.platform == .android {
            loadAndroidMetrics(childRef)
            if child.capabilities.contains(.screenshots) {
                screenshotListener = childRef.collection("screenshot_requests").document("current")
                    .addSnapshotListener { snapshot, error in
                        guard error == nil, let data = snapshot?.data() else { return }
                        let status = data["status"] as? String ?? ""
                        let message = data["message"] as? String ?? ""
                        DispatchQueue.main.async {
                            switch status {
                            case "requested": screenshotRequestStatus = "Screenshot so'rovi yuborildi — kutilmoqda"
                            case "processing": screenshotRequestStatus = "Screenshot olinmoqda…"
                            case "completed": screenshotRequestStatus = "Screenshot tayyor"
                            case "failed": screenshotRequestStatus = message.isEmpty ? "Screenshot olinmadi" : "Xato: " + message
                            default: screenshotRequestStatus = "Hali so'rov yuborilmagan"
                            }
                        }
                    }
                latestScreenshotListener = childRef.collection("screenshots")
                    .order(by: "capturedAt", descending: true).limit(to: 1)
                    .addSnapshotListener { snapshot, _ in
                        guard let document = snapshot?.documents.first else {
                            DispatchQueue.main.async { latestAndroidScreenshot = nil }
                            return
                        }
                        childRef.collection("screenshot_data").document(document.documentID).getDocument { dataSnapshot, _ in
                            guard let blob = dataSnapshot?.data()?["image"] as? Blob else { return }
                            let image = UIImage(data: blob.data)
                            DispatchQueue.main.async {
                                guard selectedChildID == child.id else { return }
                                latestAndroidScreenshot = image
                            }
                        }
                    }
            }
        }
    }

    private func loadAndroidMetrics(_ childRef: DocumentReference) {
        let startOfDay = Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000
        let collections: [(String, String, String, Color, String?)] = [
            ("calls", "Qo‘ng‘iroqlar", "phone.arrow.up.right", ParentPalette.accent, "boshlanishMs"),
            ("sms", "SMS", "message", ParentPalette.teal, "vaqtMs"),
            ("app_usage", "Ilova faolligi", "app.badge", ParentPalette.orange, "boshlanishMs"),
            ("contacts", "Kontaktlar", "person.2", ParentPalette.accent, nil),
            ("notifications", "Bildirishnomalar", "bell", ParentPalette.orange, "vaqtMs"),
            ("risk_events", "Xavf signallari", "exclamationmark.shield", ParentPalette.red, "capturedAt")
        ]
        for (collection, title, symbol, tint, dateField) in collections {
            let reference = childRef.collection(collection)
            let request: Query
            if let dateField {
                request = reference.whereField(dateField, isGreaterThanOrEqualTo: startOfDay)
            } else {
                request = reference
            }
            request.getDocuments { snapshot, _ in
                let count = snapshot?.documents.count ?? 0
                DispatchQueue.main.async {
                    guard selectedChildID == childRef.documentID else { return }
                    metrics.removeAll { $0.id == collection }
                    metrics.append(IOSMetric(id: collection, title: title, value: count, symbol: symbol, tint: tint))
                    metrics.sort { $0.id < $1.id }
                }
            }
        }
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

    private func requestAndroidScreenshot(_ child: IOSChildDevice) {
        guard child.platform == .android, child.capabilities.contains(.screenshots),
              let code = familyCode, let uid = Auth.auth().currentUser?.uid else {
            errorMessage = "Screenshot so'rovi uchun Android bola va ota-ona hisobi kerak."
            return
        }
        let requestId = UUID().uuidString
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let reference = Firestore.firestore().collection("families").document(code)
            .collection("children").document(child.id)
            .collection("screenshot_requests").document("current")
        screenshotRequestStatus = "So'rov yuborilmoqda…"
        reference.setData([
            "requestId": requestId,
            "status": "requested",
            "requestedByUid": uid,
            "createdAt": now,
            "updatedAt": now,
            "message": ""
        ]) { error in
            DispatchQueue.main.async {
                if let error {
                    screenshotRequestStatus = "So'rov yuborilmadi: " + error.localizedDescription
                } else {
                    screenshotRequestStatus = "Screenshot so'rovi yuborildi — kutilmoqda"
                }
            }
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
        childrenListener?.remove()
        locationListener?.remove()
        focusListener?.remove()
        screenshotListener?.remove()
        latestScreenshotListener?.remove()
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
        latestAndroidScreenshot = nil
        screenshotRequestStatus = "Hali so'rov yuborilmagan"
        errorMessage = nil
    }
}
