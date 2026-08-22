package uz.oilanazorati.parentcontrol.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Android'ning ROLE_SMS tekshiruvi shu xizmatning mavjudligini ham
 * talab qiladi — bu "qo'ng'iroqqa SMS bilan javob berish" (masalan,
 * kiruvchi qo'ng'iroqni rad etib, o'rniga tayyor SMS matni yuborish)
 * funksiyasi uchun ishlatiladi. Bizga bu funksiya kerak emas, shuning
 * uchun shunchaki mavjudligi yetarli — hech narsa qilmaydi.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
