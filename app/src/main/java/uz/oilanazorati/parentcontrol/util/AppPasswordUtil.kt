package uz.oilanazorati.parentcontrol.util

import java.security.MessageDigest

/**
 * Ilovaning umumiy kirish paroli uchun oddiy SHA-256 xeshlash.
 * Parolning o'zi hech qachon ochiq holda saqlanmaydi — faqat xeshi
 * SharedPreferences'da ("app_password_hash") saqlanadi.
 */
object AppPasswordUtil {
    fun hash(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
