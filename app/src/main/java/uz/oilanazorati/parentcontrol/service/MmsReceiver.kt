package uz.oilanazorati.parentcontrol.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android'ning ROLE_SMS (standart SMS ilovasi) tekshiruvi bu qabul
 * qiluvchining mavjudligini talab qiladi — aks holda ilova
 * "Приложение для SMS" ro'yxatida UMUMAN ko'rinmaydi (nafaqat
 * tanlanmaydi — butunlay ro'yxatdan tashqarida qoladi).
 *
 * MMS (rasm/video biriktirilgan xabarlar) ilovamiz uchun ahamiyatsiz —
 * biz faqat oddiy SMS statistikasini yig'amiz — shuning uchun bu yerda
 * hech narsa qilinmaydi, faqat talab qilingan komponent sifatida mavjud.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Ataylab bo'sh: MMS kontenti bilan ishlamaymiz.
    }
}
