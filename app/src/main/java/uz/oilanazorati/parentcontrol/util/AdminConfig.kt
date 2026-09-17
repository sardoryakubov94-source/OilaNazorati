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
    fun checkCurrentUserAdmin(onResult: (Boolean) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { onResult(false); return }
        FirebaseFirestore.getInstance().collection("admin_uids").document(uid).get()
            .addOnSuccessListener { onResult(it.exists()) }
            .addOnFailureListener { onResult(false) }
    }
}
