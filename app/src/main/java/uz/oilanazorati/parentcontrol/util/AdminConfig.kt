package uz.oilanazorati.parentcontrol.util

import com.google.firebase.auth.FirebaseAuth

/**
 * Ilovaning yagona administratori. Faqat shu email bilan Google orqali
 * kirilganda admin panelga (Support xabarlari, Premium so'rovlari,
 * Kartalar) kirish imkoni ochiladi.
 *
 * MUHIM: bu shunchaki UI darajasidagi ko'rinish qoidasi — HAQIQIY
 * himoya Firestore qoidalarida (`isAdmin()` funksiyasi, `firestore.rules`
 * faylida) amalga oshirilgan. Bu yerdagi tekshiruv faqat admin
 * bo'lmagan foydalanuvchiga admin tugmasi/ekranini ko'rsatmaslik uchun.
 */
object AdminConfig {
    const val ADMIN_EMAIL = "sardoryakubov94@gmail.com"

    fun isCurrentUserAdmin(): Boolean {
        val user = FirebaseAuth.getInstance().currentUser ?: return false
        return !user.isAnonymous && user.email == ADMIN_EMAIL
    }
}
