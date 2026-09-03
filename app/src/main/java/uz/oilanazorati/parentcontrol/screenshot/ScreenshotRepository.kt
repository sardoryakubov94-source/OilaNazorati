package uz.oilanazorati.parentcontrol.screenshot

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import uz.oilanazorati.parentcontrol.model.ScreenshotMetadata
import uz.oilanazorati.parentcontrol.model.ScreenshotSettings

object ScreenshotRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private fun childDoc(): com.google.firebase.firestore.DocumentReference? {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode ?: return null
        val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId ?: return null
        return db.collection("families").document(code).collection("children").document(cid)
    }

    fun listenSettings(onChange: (ScreenshotSettings) -> Unit): ListenerRegistration? =
        childDoc()?.collection("screenshot_settings")?.document("current")?.addSnapshotListener { snap, _ ->
            onChange(snap?.toObject(ScreenshotSettings::class.java) ?: ScreenshotSettings())
        }

    fun saveSettings(settings: ScreenshotSettings, onResult: (Boolean) -> Unit = {}) {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode
        val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId
        val uid = auth.currentUser?.uid
        if (code == null || cid == null || uid == null) {
            onResult(false)
            return
        }
        db.collection("families").document(code).collection("children").document(cid)
            .collection("screenshot_settings").document("current")
            .set(settings.copy(updatedAt = System.currentTimeMillis(), updatedByUid = uid))
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun reserveTrigger(key: String, onResult: (Boolean) -> Unit) {
        val child = childDoc() ?: return onResult(false)
        val ref = child.collection("screenshot_trigger_state").document(key)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (snap.exists()) {
                false
            } else {
                tx.set(ref, mapOf("status" to "reserved", "updatedAt" to System.currentTimeMillis()))
                true
            }
        }
            .addOnSuccessListener { reserved -> onResult(reserved) }
            .addOnFailureListener { onResult(false) }
    }

    fun upload(file: java.io.File, metadata: ScreenshotMetadata, onResult: (Boolean) -> Unit) {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode
        val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId
        if (code == null || cid == null) {
            onResult(false)
            return
        }

        val path = "families/$code/children/$cid/screenshots/${metadata.date}/${metadata.packageName.hashCode().toUInt().toString(16)}/${metadata.thresholdMinute}_${metadata.capturedAt}.jpg"
        val ref = storage.reference.child(path)

        ref.putFile(android.net.Uri.fromFile(file))
            .continueWithTask { ref.downloadUrl }
            .addOnSuccessListener { url ->
                // The URL is resolved successfully; metadata stores the Firebase Storage path.
                // The actual document write determines the final callback result.
                val finalMeta = metadata.copy(
                    storagePath = path,
                    status = "completed",
                    createdAt = System.currentTimeMillis()
                )
                val child = childDoc()
                if (child == null) {
                    onResult(false)
                    return@addOnSuccessListener
                }
                child.collection("screenshots")
                    .document(metadata.id)
                    .set(finalMeta)
                    .addOnSuccessListener { onResult(true) }
                    .addOnFailureListener { onResult(false) }
            }
            .addOnFailureListener { onResult(false) }
    }

    fun fetchHistory(onResult: (List<ScreenshotMetadata>) -> Unit) {
        val col = childDoc()?.collection("screenshots") ?: return onResult(emptyList())
        col.orderBy("capturedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(ScreenshotMetadata::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun downloadUrl(path: String, onResult: (String?) -> Unit) {
        storage.reference.child(path).downloadUrl
            .addOnSuccessListener { onResult(it.toString()) }
            .addOnFailureListener { onResult(null) }
    }
}
