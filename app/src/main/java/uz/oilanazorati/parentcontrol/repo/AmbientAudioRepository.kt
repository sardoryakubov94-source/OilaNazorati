package uz.oilanazorati.parentcontrol.repo

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.UUID

object AmbientAudioRepository {
    private val db = FirebaseFirestore.getInstance()

    private fun requestRef() = db.collection("families")
        .document(FirebaseRepo.familyCode.orEmpty())
        .collection("children")
        .document(FirebaseRepo.childId.orEmpty())
        .collection("mic_requests")
        .document("current")

    private fun audioCollection() = db.collection("families")
        .document(FirebaseRepo.familyCode.orEmpty())
        .collection("children")
        .document(FirebaseRepo.childId.orEmpty())
        .collection("mic_audio")

    fun requestStart(onResult: (Boolean, String?) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val family = FirebaseRepo.familyCode
        val child = FirebaseRepo.childId
        if (uid == null || family.isNullOrBlank() || child.isNullOrBlank()) {
            onResult(false, "Farzand qurilmasi tanlanmagan")
            return
        }
        val requestId = "mic_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        requestRef().set(
            mapOf(
                "requestId" to requestId,
                "status" to "requested",
                "requestedByUid" to uid,
                "requestedAt" to System.currentTimeMillis(),
                "updatedAt" to System.currentTimeMillis()
            )
        ).addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.message ?: "So'rov yuborilmadi") }
    }

    fun requestStop(onResult: (Boolean) -> Unit = {}) {
        requestRef().update(
            mapOf("status" to "stop_requested", "updatedAt" to System.currentTimeMillis())
        ).addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun listenRequestStatus(onChange: (String?, String?, String?) -> Unit): ListenerRegistration? {
        if (FirebaseRepo.familyCode.isNullOrBlank() || FirebaseRepo.childId.isNullOrBlank()) return null
        return requestRef().addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) {
                onChange(null, null, null)
                return@addSnapshotListener
            }
            val data = snap.data.orEmpty()
            onChange(
                data["requestId"] as? String,
                data["status"] as? String,
                data["sessionId"] as? String
            )
        }
    }

    fun listenAudioChunks(sessionId: String, onChunk: (Int, ByteArray) -> Unit): ListenerRegistration {
        return audioCollection()
            .whereEqualTo("sessionId", sessionId)
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                snap.documentChanges.forEach { change ->
                    if (change.type.name == "ADDED") {
                        val sequence = (change.document.getLong("sequence") ?: -1L).toInt()
                        val blob = change.document.getBlob("audio")
                        if (sequence >= 0 && blob != null) onChunk(sequence, blob.toBytes())
                    }
                }
            }
    }
}
