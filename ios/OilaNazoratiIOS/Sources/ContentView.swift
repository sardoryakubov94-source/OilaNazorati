import SwiftUI

struct ContentView: View {
    @AppStorage("family_code") private var familyCode: String = ""
    @AppStorage("child_uid") private var childUid: String = ""

    var body: some View {
        if familyCode.isEmpty || childUid.isEmpty {
            LinkView()
        } else {
            StatusView(familyCode: familyCode, childUid: childUid)
        }
    }
}
