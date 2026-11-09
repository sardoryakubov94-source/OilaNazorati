package uz.oilanazorati.parentcontrol.screenshot

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.model.FocusModeSettings

/**
 * "Kontrol rejim" sozlamalarini saqlash/tinglash. ScreenshotRepository bilan
 * bir xil families/{code}/children/{childId} hujjati ostida, alohida
 * "focus_mode_settings/current" subhujjatida saqlanadi — push-based
 * snapshot listener orqali, polling yo'q, shuning uchun batareyaga
 * qo'shimcha yuk solmaydi.
 */
object FocusModeRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private fun childDoc(): com.google.firebase.firestore.DocumentReference? {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode ?: return null
        val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId ?: return null
        return db.collection("families").document(code).collection("children").document(cid)
    }

    fun listenSettings(onChange: (FocusModeSettings) -> Unit): ListenerRegistration? =
        childDoc()?.collection("focus_mode_settings")?.document("current")
            ?.addSnapshotListener { snap, _ -> onChange(snap?.toObject(FocusModeSettings::class.java) ?: FocusModeSettings()) }

    /** Bir martalik o'qish — dialog ochilganda joriy holatni ko'rsatish uchun (listener ochiq qolmaydi). */
    fun getSettingsOnce(onResult: (FocusModeSettings) -> Unit) {
        val doc = childDoc()?.collection("focus_mode_settings")?.document("current") ?: return onResult(FocusModeSettings())
        doc.get().addOnSuccessListener { onResult(it.toObject(FocusModeSettings::class.java) ?: FocusModeSettings()) }
            .addOnFailureListener { onResult(FocusModeSettings()) }
    }

    fun saveSettings(settings: FocusModeSettings, onResult: (Boolean) -> Unit = {}) {
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode
        val cid = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId
        val uid = auth.currentUser?.uid
        if (code == null || cid == null || uid == null) { onResult(false); return }
        db.collection("families").document(code).collection("children").document(cid)
            .collection("focus_mode_settings").document("current")
            .set(settings.copy(updatedAt = System.currentTimeMillis(), updatedByUid = uid))
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }
}
