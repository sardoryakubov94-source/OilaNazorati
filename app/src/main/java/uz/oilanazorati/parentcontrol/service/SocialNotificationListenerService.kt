package uz.oilanazorati.parentcontrol.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import uz.oilanazorati.parentcontrol.model.NotificationEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * Ijtimoiy tarmoq va messenjer ilovalaridan kelgan bildirishnomalarni
 * (jo'natuvchi + xabar matni oldindan ko'rinishi) o'qiydi.
 *
 * MUHIM: bu xizmat faqat quyidagilarni o'qiy oladi:
 *  - Foydalanuvchi TIZIM SOZLAMALARIDA qo'lda "Bildirishnoma kirishi"
 *    (Notification access) ruxsatini bergan bo'lsagina ishlaydi — bu
 *    runtime permission emas, oddiy dialog orqali so'rab bo'lmaydi.
 *  - Faqat ilova BILDIRISHNOMA YUBORGANDA ishlaydi. Agar foydalanuvchi
 *    ilovani ochib, hech qanday bildirishnoma kelmasa (masalan faqat
 *    lentani ko'rib chiqsa), bu holat bu yerda umuman ko'rinmaydi —
 *    buni App Usage (ilova ishlatilgan vaqt) statistikasi qoplaydi.
 *  - Ba'zi ilovalar (masalan maxfiylik sozlamasiga qarab) bildirishnoma
 *    matnini "Yangi xabar" kabi umumiy qilib ko'rsatishi mumkin —
 *    bunday holatda haqiqiy matn bu yerga ham kelmaydi, chunki tizim
 *    darajasida yashiringan.
 */
class SocialNotificationListenerService : NotificationListenerService() {

    companion object {
        // Kuzatiladigan ijtimoiy tarmoq/messenjer ilovalari.
        // Paket nomi -> odam o'qiydigan nomi.
        private val TRACKED_APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.facebook.orca" to "Messenger",
            "com.facebook.katana" to "Facebook",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.snapchat.android" to "Snapchat",
            "com.vkontakte.android" to "VK",
            "com.discord" to "Discord",
            "com.twitter.android" to "X (Twitter)"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val appName = TRACKED_APPS[sbn.packageName] ?: return

        // Guruh xulosasi (GROUP_SUMMARY) va davom etayotgan (ongoing)
        // bildirishnomalarni o'tkazib yuboramiz — ular haqiqiy xabar emas.
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        // Ikkalasi ham bo'sh bo'lsa (masalan faqat rasm/media bildirishnomasi) — o'tkazib yuboramiz.
        if (title.isBlank() && text.isBlank()) return

        FirebaseRepo.logNotification(
            NotificationEvent(
                ilovaPaket = sbn.packageName,
                ilovaNomi = appName,
                sarlavha = title,
                matn = text,
                vaqtMs = sbn.postTime
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Hech narsa qilinmaydi — faqat kelgan bildirishnomalar qayd etiladi.
    }
}
