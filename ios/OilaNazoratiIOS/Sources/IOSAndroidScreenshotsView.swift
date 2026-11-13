import SwiftUI
import UIKit
import FirebaseAuth
import FirebaseFirestore

/// Android bolaning screenshot funksiyasi iOS ota-ona ilovasida.
/// Android bola tomoni o'zgarmaydi: so'rov `screenshot_requests/current` ga yoziladi,
/// natija `screenshots` (metadata) va `screenshot_data` (JPEG) dan o'qiladi.
private struct IOSAndroidScreenshot: Identifiable {
    let id: String
    let appLabel: String
    let packageName: String
    let capturedAt: Date
    let riskCategory: String
}

struct IOSAndroidScreenshotsView: View {
    let familyCode: String
    let childID: String

    @State private var items: [IOSAndroidScreenshot] = []
    @State private var loading = false
    @State private var requestStatus = ""
    @State private var requestMessage = ""
    @State private var statusListener: ListenerRegistration?
    @State private var selectedImage: UIImage?
    @State private var showingImage = false
    @State private var errorText: String?

    private var childRef: DocumentReference {
        Firestore.firestore()
            .collection("families").document(familyCode)
            .collection("children").document(childID)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Screenshotlar", systemImage: "photo.on.rectangle")
                    .font(.headline)
                Spacer()
                Button {
                    reload()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(loading)
            }

            Button {
                requestScreenshot()
            } label: {
                Label("Hozir screenshot olish", systemImage: "camera.viewfinder")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(requestStatus == "requested" || requestStatus == "processing")

            if !requestStatus.isEmpty {
                Text(statusText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let errorText {
                Text(errorText).font(.caption).foregroundStyle(.red)
            }

            if loading {
                ProgressView().frame(maxWidth: .infinity)
            } else if items.isEmpty {
                Text("Hozircha screenshot yo'q.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(items) { item in
                    Button {
                        openImage(item)
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "photo")
                                .frame(width: 30)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.appLabel.isEmpty ? item.packageName : item.appLabel)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.primary)
                                Text(dateText(item.capturedAt) + (item.riskCategory.isEmpty ? "" : " • " + item.riskCategory))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
        .sheet(isPresented: $showingImage) {
            NavigationView {
                ScrollView([.vertical, .horizontal]) {
                    if let selectedImage {
                        Image(uiImage: selectedImage)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                    }
                }
                .navigationTitle("Screenshot")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("Yopish") { showingImage = false }
                    }
                }
            }
        }
        .onAppear {
            reload()
            listenStatus()
        }
        .onDisappear {
            statusListener?.remove()
            statusListener = nil
        }
    }

    private var statusText: String {
        switch requestStatus {
        case "requested": return "So'rov yuborildi, bola qurilmasi javobini kutmoqda…"
        case "processing": return "Bola qurilmasida screenshot olinmoqda…"
        case "completed": return "Screenshot tayyor. Ro'yxatni yangilang."
        case "failed": return requestMessage.isEmpty ? "Screenshot olinmadi." : requestMessage
        default: return requestMessage.isEmpty ? requestStatus : requestMessage
        }
    }

    private func dateText(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    private func reload() {
        loading = true
        errorText = nil
        childRef.collection("screenshots")
            .order(by: "capturedAt", descending: true)
            .limit(to: 50)
            .getDocuments { snapshot, error in
                DispatchQueue.main.async {
                    loading = false
                    if let error {
                        errorText = error.localizedDescription
                        return
                    }
                    items = (snapshot?.documents ?? []).map { doc -> IOSAndroidScreenshot in
                        let data = doc.data()
                        let millis = (data["capturedAt"] as? NSNumber)?.doubleValue ?? 0
                        return IOSAndroidScreenshot(
                            id: doc.documentID,
                            appLabel: data["appLabel"] as? String ?? "",
                            packageName: data["packageName"] as? String ?? "",
                            capturedAt: Date(timeIntervalSince1970: millis / 1000),
                            riskCategory: data["riskCategory"] as? String ?? ""
                        )
                    }
                }
            }
    }

    private func listenStatus() {
        statusListener?.remove()
        statusListener = childRef.collection("screenshot_requests").document("current")
            .addSnapshotListener { snapshot, _ in
                let data = snapshot?.data()
                DispatchQueue.main.async {
                    requestStatus = data?["status"] as? String ?? ""
                    requestMessage = data?["message"] as? String ?? ""
                    if requestStatus == "completed" { reload() }
                }
            }
    }

    private func requestScreenshot() {
        guard let uid = Auth.auth().currentUser?.uid else {
            errorText = "Ota-ona akkaunti aniqlanmadi. Qayta kiring."
            return
        }
        errorText = nil
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let payload: [String: Any] = [
            "requestId": UUID().uuidString,
            "status": "requested",
            "requestedByUid": uid,
            "createdAt": now,
            "updatedAt": now,
            "message": ""
        ]
        childRef.collection("screenshot_requests").document("current").setData(payload) { error in
            if let error {
                DispatchQueue.main.async { errorText = error.localizedDescription }
            }
        }
    }

    private func openImage(_ item: IOSAndroidScreenshot) {
        errorText = nil
        childRef.collection("screenshot_data").document(item.id).getDocument { snapshot, error in
            DispatchQueue.main.async {
                if let error {
                    errorText = error.localizedDescription
                    return
                }
                if let data = snapshot?.data()?["image"] as? Data, let image = UIImage(data: data) {
                    selectedImage = image
                    showingImage = true
                } else {
                    errorText = "Rasm topilmadi."
                }
            }
        }
    }
}
