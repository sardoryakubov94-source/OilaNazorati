package uz.oilanazorati.parentcontrol.util

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Oila Nazorati administratorlari.
 * Faqat "admin_uids" Firestore kolleksiyasida ro'yxatdan o'tgan
 * hisoblar bilan kirilganda admin panel ochiladi.
 *
 * MUHIM (xavfsizlik/maxfiylik): ilgari bu yerda ikkita shaxsiy Gmail
 * manzili to'g'ridan-to'g'ri kodga yozilgan edi — bu APK ichida
 * (har kim uni dekompilyatsiya qilib, oddiy matn sifatida o'qiy oladi)
 * ochiq turardi. Endi tekshiruv Firestore orqali, UID bo'yicha
 * amalga oshiriladi, shuning uchun APK'da endi hech qanday email
 * manzili saqlanmaydi. Haqiqiy himoya baribir `firestore.rules`dagi
 * isAdmin() orqali ta'minlanadi — bu klass faqat UI'da tugma/panelni
 * ko'rsatish-ko'rsatmaslik uchun.
 */
object AdminConfig {
    // Egasi (asosiy admin) emailining SHA-256 xeshi. Oddiy email APK ichida saqlanmaydi.
    private const val OWNER_EMAIL_SHA256 = "e070756a169282dda0335ab2350a729f342838c411e330906957b7a2a253405b"

    /** Hozirgi hisob egasi (asosiy admin) hisobimi. Faqat shu hisob premium tarixini yashira/ko'rsata oladi. */
    fun isOwner(): Boolean {
        val email = FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase(java.util.Locale.ROOT) ?: return false
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(email.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } == OWNER_EMAIL_SHA256
    }

    fun checkCurrentUserAdmin(onResult: (Boolean) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { onResult(false); return }
        FirebaseFirestore.getInstance().collection("admin_uids").document(uid).get()
            .addOnSuccessListener { onResult(it.exists()) }
            .addOnFailureListener { onResult(false) }
    }
}
