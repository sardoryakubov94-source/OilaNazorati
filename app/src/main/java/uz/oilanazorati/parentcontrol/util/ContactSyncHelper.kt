package uz.oilanazorati.parentcontrol.util

import android.content.Context
import android.provider.ContactsContract
import uz.oilanazorati.parentcontrol.model.ContactMapping
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * MUHIM PRINSIP: bu klass faqat qurilmaning MANZILLAR KITOBIDA (Contacts)
 * ISM BILAN SAQLANGAN yozuvlarni o'qiydi — ya'ni oila a'zosi ataylab
 * "Onam", "Dadam" kabi nom berib saqlagan raqamlarni. Bunday raqam allaqachon
 * oila ichida ma'lum va sir emas.
 *
 * Kontaktlarga SAQLANMAGAN (notanish) raqamlar bu yerga umuman kirmaydi —
 * ContentResolver so'rovi faqat CommonDataKinds.Phone jadvalidan o'tadi, u
 * esa faqat saqlangan kontaktlarni qaytaradi (chaqiruv jurnali emas).
 *
 * Har bir kontakt uchun RAQAM Firestore'ga hech qachon yuborilmaydi —
 * faqat ContactAnonymizer.hash() natijasi (xuddi qo'ng'iroq/SMS
 * statistikasidagi bilan bir xil identifikator, shuning uchun ranglar
 * mos tushadi).
 */
object ContactSyncHelper {

    /** Qurilmadagi saqlangan kontaktlarni o'qib, Firestore bilan sinxronlaydi. */
    fun syncNow(context: Context) {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return

        val result = LinkedHashMap<String, String>() // kontaktHash -> nomi
        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = if (nameIdx >= 0) it.getString(nameIdx) else null
                val number = if (numberIdx >= 0) it.getString(numberIdx) else null
                // Faqat haqiqiy ismi bo'lgan yozuvlar — bo'shini o'tkazib yuboramiz
                if (name.isNullOrBlank() || number.isNullOrBlank()) continue

                // Raqam faqat shu yerda, xotirada, hash olish uchun ishlatiladi —
                // hech qayerga yozilmaydi.
                val hash = ContactAnonymizer.hash(context, number)
                result[hash] = name
            }
        }

        val mappings = result.map { (hash, name) -> ContactMapping(nomi = name, kontaktHash = hash) }
        FirebaseRepo.syncSavedContacts(mappings)
    }
}
