package uz.oilanazorati.parentcontrol.util

import com.google.firebase.auth.FirebaseAuth

/**
 * Oila Nazorati administratorlari.
 * Faqat ro'yxatdagi Google hisoblari bilan kirilganda admin panel ochiladi.
 *
 * MUHIM: haqiqiy Firestore himoyasi `firestore.rules` ichidagi isAdmin()
 * funksiyasi bilan ham tekshiriladi. Bu tekshiruv esa ilovada admin
 * tugmasi/panelini ko'rsatish uchun ishlatiladi.
 */
object AdminConfig {
    private val ADMIN_EMAILS = setOf(
        "sardoryakubov94@gmail.com",
        "marselovgayniddin4@gmail.com"
    )

    fun isCurrentUserAdmin(): Boolean {
        val email = FirebaseAuth.getInstance().currentUser?.email ?: return false
        return email.trim().lowercase() in ADMIN_EMAILS
    }
}
