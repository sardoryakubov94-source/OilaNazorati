package uz.oilanazorati.parentcontrol.screenshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.model.ScreenshotMetadata
import uz.oilanazorati.parentcontrol.model.ScreenshotSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object ScreenshotRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private const val MAX_IMAGE_BYTES = 700 * 1024
    private const val STALE_REQUEST_MS = 15_000L
    private fun childDoc(): com.google.firebase.firestore.DocumentReference? {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode ?: return null
        val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId ?: return null
        return db.collection("families").document(code).collection("children").document(cid)
    }
    fun listenSettings(onChange: (ScreenshotSettings) -> Unit): ListenerRegistration? = childDoc()?.collection("screenshot_settings")?.document("current")?.addSnapshotListener { snap, _ -> onChange(snap?.toObject(ScreenshotSettings::class.java) ?: ScreenshotSettings()) }
    fun listenScreenshotRequests(onRequest: (String) -> Unit): ListenerRegistration? = childDoc()?.collection("screenshot_requests")?.document("current")?.addSnapshotListener { snap, error -> if (error == null && snap?.exists() == true && snap.getString("status") == "requested") onRequest(snap.getString("requestId") ?: snap.id) }
    fun requestScreenshot(onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val child = childDoc(); val uid = auth.currentUser?.uid
        if (child == null || uid == null) { onResult(false, "Farzand qurilmasi ulanmagan yoki ota-ona akkaunti aniqlanmadi"); return }
        val requestId = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        child.collection("screenshot_requests").document("current").set(mapOf("requestId" to requestId, "status" to "requested", "requestedByUid" to uid, "createdAt" to now, "updatedAt" to now, "message" to ""))
            .addOnSuccessListener { onResult(true, requestId) }.addOnFailureListener { e -> onResult(false, e.message ?: "So'rov yuborilmadi") }
    }
    fun listenScreenshotRequestStatus(onChange: (requestId: String, status: String) -> Unit): ListenerRegistration? = childDoc()?.collection("screenshot_requests")?.document("current")?.addSnapshotListener { snap, error -> if (error == null && snap?.exists() == true) { val id = snap.getString("requestId"); if (id != null) onChange(id, snap.getString("status") ?: "") } }
    fun failStaleScreenshotRequest() {
        val child = childDoc() ?: return; val ref = child.collection("screenshot_requests").document("current")
        ref.get().addOnSuccessListener { snap ->
            if (!snap.exists()) return@addOnSuccessListener
            val status = snap.getString("status") ?: return@addOnSuccessListener
            if (status != "requested" && status != "processing") return@addOnSuccessListener
            val updatedAt = snap.getLong("updatedAt") ?: snap.getLong("createdAt") ?: return@addOnSuccessListener
            if (System.currentTimeMillis() - updatedAt < STALE_REQUEST_MS) return@addOnSuccessListener
            ref.set(mapOf("status" to "failed", "message" to "Screenshot so'rovi vaqtida yakunlanmadi", "updatedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
        }
    }
    fun markScreenshotRequest(requestId: String, status: String, message: String = "") { childDoc()?.collection("screenshot_requests")?.document("current")?.set(mapOf("requestId" to requestId, "status" to status, "message" to message, "updatedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge()) }
    fun saveSettings(settings: ScreenshotSettings, onResult: (Boolean) -> Unit = {}) {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode; val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId; val uid = auth.currentUser?.uid
        if (code == null || cid == null || uid == null) { onResult(false); return }
        db.collection("families").document(code).collection("children").document(cid).collection("screenshot_settings").document("current").set(settings.copy(updatedAt = System.currentTimeMillis(), updatedByUid = uid)).addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }
    fun reserveTrigger(key: String, onResult: (Boolean) -> Unit) {
        val child = childDoc() ?: return onResult(false); val ref = child.collection("screenshot_trigger_state").document(key)
        db.runTransaction { tx -> val snap = tx.get(ref); if (snap.exists()) false else { tx.set(ref, mapOf("status" to "reserved", "updatedAt" to System.currentTimeMillis())); true } }.addOnSuccessListener { onResult(it) }.addOnFailureListener { onResult(false) }
    }
    fun updateProjectionStatus(active: Boolean) { childDoc()?.set(mapOf("screenProjectionActive" to active, "screenProjectionUpdatedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge()) }
    fun updateAccessibilityStatus(active: Boolean) { childDoc()?.set(mapOf("screenAccessibilityActive" to active, "screenAccessibilityUpdatedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge()) }
    fun listenProjectionStatus(onChange: (active: Boolean, updatedAt: Long) -> Unit): ListenerRegistration? = childDoc()?.addSnapshotListener { snap, _ -> val p = snap?.getBoolean("screenProjectionActive") == true; val a = snap?.getBoolean("screenAccessibilityActive") == true; val pt = snap?.getLong("screenProjectionUpdatedAt") ?: 0L; val at = snap?.getLong("screenAccessibilityUpdatedAt") ?: 0L; onChange(p || a, maxOf(pt, at)) }
    fun upload(file: File, metadata: ScreenshotMetadata, onResult: (Boolean) -> Unit) {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode; val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId
        if (code == null || cid == null || auth.currentUser?.uid != cid) { onResult(false); return }
        try {
            val imageBytes = prepareImage(file); if (imageBytes.isEmpty() || imageBytes.size > MAX_IMAGE_BYTES) { onResult(false); return }
            val finalMeta = metadata.copy(storagePath = "firestore://screenshot_data/${metadata.id}", status = "completed", contentType = "image/jpeg", byteSize = imageBytes.size.toLong(), createdAt = System.currentTimeMillis())
            val child = childDoc() ?: run { onResult(false); return }; val dataRef = child.collection("screenshot_data").document(metadata.id); val metaRef = child.collection("screenshots").document(metadata.id)
            dataRef.set(mapOf("image" to Blob.fromBytes(imageBytes), "contentType" to "image/jpeg", "byteSize" to imageBytes.size.toLong(), "createdAt" to System.currentTimeMillis())).continueWithTask { metaRef.set(finalMeta) }.addOnSuccessListener { onResult(true) }.addOnFailureListener { dataRef.delete(); onResult(false) }
        } catch (_: Exception) { onResult(false) }
    }
    fun fetchHistory(onResult: (List<ScreenshotMetadata>) -> Unit) { val col = childDoc()?.collection("screenshots") ?: return onResult(emptyList()); col.orderBy("capturedAt", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(100).get().addOnSuccessListener { onResult(it.documents.mapNotNull { d -> d.toObject(ScreenshotMetadata::class.java) }) }.addOnFailureListener { onResult(emptyList()) } }
    fun loadImageBytes(id: String, onResult: (ByteArray?) -> Unit) { val child = childDoc() ?: return onResult(null); child.collection("screenshot_data").document(id).get().addOnSuccessListener { onResult(it.getBlob("image")?.toBytes()) }.addOnFailureListener { onResult(null) } }
    fun deleteScreenshot(id: String, onResult: (Boolean) -> Unit = {}) {
        val child = childDoc() ?: return onResult(false)
        val dataRef = child.collection("screenshot_data").document(id)
        val metaRef = child.collection("screenshots").document(id)
        db.batch().delete(dataRef).delete(metaRef).commit()
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }
    private fun prepareImage(file: File): ByteArray {
        val original = file.readBytes(); if (original.size <= MAX_IMAGE_BYTES) return original; val source = BitmapFactory.decodeByteArray(original, 0, original.size) ?: return ByteArray(0)
        try { var width = source.width; var height = source.height; var quality = 78; repeat(8) { val scaled = Bitmap.createScaledBitmap(source, width, height, true); try { val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, quality, out); val bytes = out.toByteArray(); if (bytes.size <= MAX_IMAGE_BYTES) return bytes } finally { scaled.recycle() }; width = (width * .82f).toInt().coerceAtLeast(480); height = (height * .82f).toInt().coerceAtLeast(480); quality = (quality - 5).coerceAtLeast(40) }; return ByteArray(0) } finally { source.recycle() }
    }
}
