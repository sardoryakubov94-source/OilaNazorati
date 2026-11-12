import SwiftUI
import FirebaseCore

@main
struct OilaNazoratiIOSApp: App {
    init() {
        FirebaseApp.configure()
    }

    var body: some Scene {
        WindowGroup {
            ScreenTimeHomeView()
        }
    }
}
