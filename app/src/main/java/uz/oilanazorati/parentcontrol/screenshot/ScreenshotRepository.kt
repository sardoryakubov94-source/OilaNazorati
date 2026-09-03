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

object ScreenshotRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    // Firestore document limit is 1 MiB. Keep image bytes comfortably below it.
    private const val MAX_IMAGE_BYTES = 700 * 1024

    private fun childDoc(): com.google.firebase.firestore.DocumentReference? {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode ?: return null
        val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId ?: return null
        return db.collection("families").document(code)
            .collection("children").document(cid)
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

    fun upload(file: File, metadata: ScreenshotMetadata, onResult: (Boolean) -> Unit) {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode
        val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId
        if (code == null || cid == null || auth.currentUser?.uid != cid) {
            onResult(false)
            return
        }

        try {
            val imageBytes = prepareImage(file)
            if (imageBytes.isEmpty() || imageBytes.size > MAX_IMAGE_BYTES) {
                onResult(false)
                return
            }

            val finalMeta = metadata.copy(
                storagePath = "firestore://screenshot_data/${metadata.id}",
                status = "completed",
                contentType = "image/jpeg",
                byteSize = imageBytes.size.toLong(),
                createdAt = System.currentTimeMillis()
            )

            val child = childDoc() ?: run {
                onResult(false)
                return
            }

            val dataRef = child.collection("screenshot_data").document(metadata.id)
            val metaRef = child.collection("screenshots").document(metadata.id)

            // Store the JPEG as Firestore bytes instead of Firebase Storage.
            // This keeps the feature usable on the Firebase Spark plan.
            dataRef.set(
                mapOf(
                    "image" to Blob.fromBytes(imageBytes),
                    "contentType" to "image/jpeg",
                    "byteSize" to imageBytes.size.toLong(),
                    "createdAt" to System.currentTimeMillis()
                )
            ).continueWithTask {
                metaRef.set(finalMeta)
            }.addOnSuccessListener {
                onResult(true)
            }.addOnFailureListener {
                dataRef.delete()
                onResult(false)
            }
        } catch (_: Exception) {
            onResult(false)
        }
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

    fun loadImageBytes(id: String, onResult: (ByteArray?) -> Unit) {
        val child = childDoc() ?: return onResult(null)
        child.collection("screenshot_data").document(id).get()
            .addOnSuccessListener { snap ->
                onResult(snap.getBlob("image")?.toBytes())
            }
            .addOnFailureListener { onResult(null) }
    }

    private fun prepareImage(file: File): ByteArray {
        val original = file.readBytes()
        if (original.size <= MAX_IMAGE_BYTES) return original

        val source = BitmapFactory.decodeByteArray(original, 0, original.size)
            ?: return ByteArray(0)

        try {
            var width = source.width
            var height = source.height
            var quality = 78

            repeat(8) {
                val out = ByteArrayOutputStream()
                source.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                if (bytes.size <= MAX_IMAGE_BYTES) return bytes

                width = (width * 0.85f).toInt().coerceAtLeast(480)
                height = (height * 0.85f).toInt().coerceAtLeast(480)
                quality = (quality - 5).coerceAtLeast(45)
            }

            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            return try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray().takeIf { it.size <= MAX_IMAGE_BYTES } ?: ByteArray(0)
            } finally {
                scaled.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
