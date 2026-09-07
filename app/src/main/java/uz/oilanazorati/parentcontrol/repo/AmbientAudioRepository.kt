package uz.oilanazorati.parentcontrol.repo

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.UUID

object AmbientAudioRepository {
    private const val TAG = "AmbientAudioRepo"
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

    // --- WebRTC signalizatsiya (Firestore faqat SDP/ICE matnlarini uzatadi,
    // ovozning o'zi to'g'ridan-to'g'ri, peer-to-peer uzatiladi — bu eski
    // "mic_audio" bo'lak-bo'lak yozish usuliga qaraganda Firestore
    // limitini deyarli sarflamaydi). ---

    /** Ota-ona qurilmasi WebRTC taklifini (SDP offer) bola qurilmasiga yuboradi. */
    fun requestStartWebRtc(offerSdp: String, onResult: (Boolean, String?, String?) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val family = FirebaseRepo.familyCode
        val child = FirebaseRepo.childId
        if (uid == null || family.isNullOrBlank() || child.isNullOrBlank()) {
            onResult(false, "Farzand qurilmasi tanlanmagan", null)
            return
        }
        val requestId = "web_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        requestRef().set(
            mapOf(
                "requestId" to requestId,
                "status" to "requested",
                "transport" to "webrtc",
                "webrtcOffer" to offerSdp,
                "parentCandidates" to emptyList<Any>(),
                "childCandidates" to emptyList<Any>(),
                "requestedByUid" to uid,
                "requestedAt" to System.currentTimeMillis(),
                "updatedAt" to System.currentTimeMillis()
            )
        ).addOnSuccessListener { onResult(true, null, requestId) }
            .addOnFailureListener { onResult(false, it.message ?: "So'rov yuborilmadi", null) }
    }

    /** Ota-onaning o'z ICE manzilini bola qurilmasiga yetkazish uchun Firestore'ga qo'shadi. */
    fun sendParentIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        requestRef().update(
            "parentCandidates",
            com.google.firebase.firestore.FieldValue.arrayUnion(
                mapOf("candidate" to candidate, "sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex)
            )
        )
    }

    /**
     * WebRTC sessiyasini kuzatadi: bola qurilmasi javobi (SDP answer), uning
     * ICE manzillari va umumiy holatni (active/failed/stopped) qaytaradi.
     */
    fun listenWebRtcSession(
        requestId: String,
        onAnswer: (String) -> Unit,
        onChildCandidate: (String, String?, Int) -> Unit,
        onStatus: (String, String?) -> Unit
    ): ListenerRegistration {
        val applied = HashSet<String>()
        return requestRef().addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            val data = snap.data.orEmpty()
            if (data["requestId"] as? String != requestId) return@addSnapshotListener
            (data["status"] as? String)?.let { onStatus(it, data["error"] as? String) }
            (data["webrtcAnswer"] as? String)?.let { onAnswer(it) }
            val candidates = data["childCandidates"] as? List<*> ?: emptyList<Any>()
            candidates.forEach { raw ->
                val m = raw as? Map<*, *> ?: return@forEach
                val candidate = m["candidate"] as? String ?: return@forEach
                if (applied.add(candidate)) {
                    val mid = m["sdpMid"] as? String
                    val index = (m["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                    onChildCandidate(candidate, mid, index)
                }
            }
        }
    }

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
                if (error != null) Log.e(TAG, "Mic request listener error", error)
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
        val query = audioCollection()
            .whereEqualTo("sessionId", sessionId)
            .orderBy("sequence", Query.Direction.ASCENDING)

        var fallbackStarted = false
        var fallbackRegistration: ListenerRegistration? = null

        val primaryRegistration = query.addSnapshotListener { snap, error ->
            if (error != null) {
                Log.e(TAG, "Ordered mic_audio query failed; using client-side sequence ordering", error)
                if (!fallbackStarted) {
                    fallbackStarted = true
                    fallbackRegistration = audioCollection()
                        .whereEqualTo("sessionId", sessionId)
                        .addSnapshotListener { fallbackSnap, fallbackError ->
                            if (fallbackError != null || fallbackSnap == null) {
                                if (fallbackError != null) Log.e(TAG, "Fallback mic_audio listener error", fallbackError)
                                return@addSnapshotListener
                            }
                            fallbackSnap.documentChanges.forEach { change ->
                                if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                    val sequence = (change.document.getLong("sequence") ?: -1L).toInt()
                                    val blob = change.document.getBlob("audio")
                                    if (sequence >= 0 && blob != null) onChunk(sequence, blob.toBytes())
                                }
                            }
                        }
                }
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            snap.documentChanges.forEach { change ->
                if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                    val sequence = (change.document.getLong("sequence") ?: -1L).toInt()
                    val blob = change.document.getBlob("audio")
                    if (sequence >= 0 && blob != null) onChunk(sequence, blob.toBytes())
                }
            }
        }

        return object : ListenerRegistration {
            override fun remove() {
                primaryRegistration.remove()
                fallbackRegistration?.remove()
            }
        }
    }
}
