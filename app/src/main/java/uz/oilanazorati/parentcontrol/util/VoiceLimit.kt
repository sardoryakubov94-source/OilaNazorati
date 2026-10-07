package uz.oilanazorati.parentcontrol.util

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Ovoz" funksiyasi uchun kunlik limit: har bir ota-ona hisobiga kuniga 30 daqiqa.
 * Limit har kuni Toshkent vaqti (UTC+5) bilan yarim tunda qayta tiklanadi.
 * Sarflangan vaqt parents/{uid} hujjatida (voiceDay, voiceSeconds) saqlanadi —
 * shu sababli ilova va veb-panel BIR XIL limitni ishlatadi.
 * Adminlar (admin_uids) uchun cheklov yo'q.
 */
object VoiceLimit {
    const val DAILY_SECONDS = 30 * 60

    private val db get() = FirebaseFirestore.getInstance()

    fun dayKey(): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT+05:00")
        return f.format(Date())
    }

    fun format(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    /** onResult(isAdmin, qolganSoniya). qolganSoniya == null — tekshirib bo'lmadi (internet/xato). */
    fun load(onResult: (Boolean, Int?) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return onResult(false, null)
        db.collection("admin_uids").document(uid).get()
            .addOnSuccessListener { admin ->
                if (admin.exists()) onResult(true, Int.MAX_VALUE) else readUsage(uid, onResult)
            }
            .addOnFailureListener { readUsage(uid, onResult) }
    }

    private fun readUsage(uid: String, onResult: (Boolean, Int?) -> Unit) {
        db.collection("parents").document(uid).get()
            .addOnSuccessListener { doc ->
                val used = if (doc.getString("voiceDay") == dayKey()) (doc.getLong("voiceSeconds") ?: 0L).toInt() else 0
                onResult(false, (DAILY_SECONDS - used).coerceAtLeast(0))
            }
            .addOnFailureListener { onResult(false, null) }
    }

    /** Sarflangan soniyalarni qo'shadi (kun almashgan bo'lsa noldan boshlaydi). Adminlar uchun chaqirilmaydi. */
    fun addUsage(seconds: Int) {
        if (seconds <= 0) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = db.collection("parents").document(uid)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val today = dayKey()
            val used = if (snap.getString("voiceDay") == today) (snap.getLong("voiceSeconds") ?: 0L) else 0L
            tx.set(ref, mapOf("voiceDay" to today, "voiceSeconds" to used + seconds), SetOptions.merge())
            null
        }
    }
}
