import SwiftUI
import FirebaseCore

/// Ilova kirish nuqtasi. GoogleService-Info.plist fayli (Firebase konsolidan
/// olinadigan, Android bilan BIR XIL Firebase loyihasiga iOS ilova sifatida
/// qo'shilganda yaratiladi) Xcode loyihasiga qo'shilgan bo'lishi SHART —
/// aks holda FirebaseApp.configure() ishlamaydi. Qarang: README.md.
@main
struct OilaNazoratiApp: App {
    init() {
        FirebaseApp.configure()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
