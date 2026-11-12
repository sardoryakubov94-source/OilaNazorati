import SwiftUI

struct ContentView: View {
    @AppStorage("family_code") private var familyCode: String = ""
    @AppStorage("child_uid") private var childUid: String = ""
    @AppStorage("device_role") private var deviceRoleRaw: String = DeviceRole.unselected.rawValue

    private var resolvedRole: DeviceRole {
        if let role = DeviceRole(rawValue: deviceRoleRaw), role != .unselected {
            return role
        }
        // Old iOS installations already linked as a child must keep working.
        return (!familyCode.isEmpty && !childUid.isEmpty) ? .child : .unselected
    }

    var body: some View {
        Group {
            switch resolvedRole {
            case .unselected:
                DeviceRoleSelectionView { role in
                    deviceRoleRaw = role.rawValue
                }
            case .child:
                if familyCode.isEmpty || childUid.isEmpty {
                    LinkView()
                } else {
                    StatusView(familyCode: familyCode, childUid: childUid)
                }
            case .parent:
                IOSParentModePlaceholderView {
                    deviceRoleRaw = DeviceRole.unselected.rawValue
                }
            }
        }
        .onAppear {
            // Migrate already-paired installations without interrupting child mode.
            if deviceRoleRaw == DeviceRole.unselected.rawValue,
               !familyCode.isEmpty, !childUid.isEmpty {
                deviceRoleRaw = DeviceRole.child.rawValue
            }
        }
    }
}

private struct DeviceRoleSelectionView: View {
    let onSelect: (DeviceRole) -> Void

    var body: some View {
        NavigationView {
            VStack(spacing: 18) {
                Image(systemName: "shield.lefthalf.filled")
                    .font(.system(size: 54))
                    .foregroundColor(.teal)
                Text("Oila Nazorati")
                    .font(.largeTitle.bold())
                Text("Bu qurilma kim tomonidan ishlatiladi?")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 8)

                Button {
                    onSelect(.child)
                } label: {
                    Label("Bu farzandning qurilmasi", systemImage: "iphone")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    onSelect(.parent)
                } label: {
                    Label("Bu ota-onaning qurilmasi", systemImage: "person.crop.rectangle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

                Text("Qurilma roli keyingi bosqichlarda sozlamalardan almashtirilishi mumkin.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)
                Spacer()
            }
            .padding(24)
            .padding(.top, 40)
            .navigationBarHidden(true)
        }
    }
}

private struct IOSParentModePlaceholderView: View {
    let onChangeRole: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "person.crop.rectangle")
                .font(.system(size: 52))
                .foregroundColor(.teal)
            Text("Ota-ona rejimi")
                .font(.title2.bold())
            Text("Ota-ona paneli hali ulanmagan. Hozircha bu rejim faqat qurilma rolini belgilaydi; nazorat funksiyalari ishlayapti deb hisoblanmaydi.")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal, 28)
            Button("Rolni qayta tanlash", action: onChangeRole)
                .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
