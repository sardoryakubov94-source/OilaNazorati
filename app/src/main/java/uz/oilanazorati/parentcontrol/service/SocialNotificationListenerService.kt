package uz.oilanazorati.parentcontrol.service

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import uz.oilanazorati.parentcontrol.model.NotificationEvent
import uz.oilanazorati.parentcontrol.model.RiskEvent
import uz.oilanazorati.parentcontrol.risk.RiskAnalysisEngine
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.TopUsedAppsHelper

/**
 * Ijtimoiy tarmoq va messenjer ilovalaridan kelgan bildirishnomalarni
 * (jo'natuvchi + xabar matni oldindan ko'rinishi) o'qiydi.
 *
 * IKKI BOSQICHLI CHEKLOV (Firestore yozuv kvotasini tejash + premium
 * farqlash uchun):
 *  1) FAQAT premium oilalar uchun ishlaydi (`ownerPremium == true`,
 *     bola hujjatidan o'qiladi — bola parents/{uid}ni o'qiy olmagani
 *     uchun bu bayroq ota-ona tomonidan bola hujjatiga ko'chirib
 *     qo'yiladi, qarang: FirebaseRepo.syncPremiumFlagToChildren).
 *  2) Premium bo'lsa ham, faqat bolaning BUGUNGI eng ko'p ISHLATGAN
 *     (ekranda eng ko'p vaqt o'tkazgan — bildirishnoma SONI emas)
 *     top-3 ilovasidan kelgan bildirishnomalar o'tkaziladi. Masalan
 *     Snapchat tez-tez bildirishnoma yuborsa-yu, bola uni deyarli
 *     ochmasa — u top-3'ga kirmaydi va undan bildirishnoma kelmaydi.
 *     Bu hisoblash mexanizmi avtomatik skrinshot funksiyasi bilan
 *     BIR XIL (TopUsedAppsHelper) — dastur bo'ylab yagona ta'rif.
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

        // Ba'zi ilovalar (masalan Instagram) BITTA xabar uchun bildirishnomani
        // bir necha marta "yangilaydi" (media yuklanish holati, o'qilgan belgisi
        // va h.k.) — bu odatda BIR NECHA SONIYA (ba'zan millisekundlar) ichida
        // sodir bo'ladi, har safar onNotificationPosted() qayta chaqiriladi.
        // Oyna qisqa ushlanadi — aks holda bola juda tez ketma-ket YOZGAN
        // ikkita HAQIQIY, boshqa-boshqa xabarini (masalan ikki marta "ha")
        // xato ravishda bitta deb hisoblab, ikkinchisini yo'qotib qo'yish xavfi bor.
        private const val DEDUPE_WINDOW_MS = 5_000L

        // Top-3 ilovalar ro'yxati shu oraliqda qayta hisoblanadi (lokal,
        // Firestore bilan bog'liq emas — UsageStatsManager qurilmaning
        // o'zida ishlaydi, hech qanday kvota sarflamaydi).
        private const val TOP_APPS_REFRESH_MS = 10 * 60 * 1000L
    }

    // Xotirada saqlanadigan oxirgi ko'rilgan bildirishnomalar keshi. Servis
    // qayta ishga tushganda tozalanadi — bu qabul qilinadi, chunki bu holat
    // kamdan-kam va zararsiz (eng ko'pi bilan bitta takror o'tib ketishi mumkin).
    private val recentlySeen = LinkedHashMap<String, Long>()

    private val handler = Handler(Looper.getMainLooper())
    private var premiumListener: com.google.firebase.firestore.ListenerRegistration? = null
    @Volatile private var isOwnerPremium: Boolean = false
    @Volatile private var topApps: Set<String> = emptySet()

    private val refreshTopAppsRunnable = object : Runnable {
        override fun run() {
            topApps = TopUsedAppsHelper.computeTopApps(applicationContext, 3)
            handler.postDelayed(this, TOP_APPS_REFRESH_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        handler.post(refreshTopAppsRunnable)
        registerPremiumListener()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        handler.removeCallbacks(refreshTopAppsRunnable)
        premiumListener?.remove()
        premiumListener = null
    }

    /** Bola o'zi ulangan hujjatidagi "ownerPremium" bayrog'ini tinglaydi
     * (parents/{uid}ni o'zi o'qiy olmagani uchun ota-ona tomonidan shu
     * hujjatga ko'chirib qo'yilgan qiymat — qarang: FirebaseRepo.syncPremiumFlagToChildren). */
    private fun registerPremiumListener() {
        val family = FirebaseRepo.familyCode ?: return
        val child = FirebaseRepo.childId
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: return
        premiumListener = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("families").document(family).collection("children").document(child)
            .addSnapshotListener { snap, _ ->
                isOwnerPremium = snap?.getBoolean("ownerPremium") == true
            }
    }

    private fun isDuplicate(key: String, nowMs: Long): Boolean {
        // Eskirgan yozuvlarni tozalab boramiz — xotira cheksiz o'smasin.
        recentlySeen.entries.removeAll { nowMs - it.value > DEDUPE_WINDOW_MS }
        val lastSeen = recentlySeen[key]
        recentlySeen[key] = nowMs
        return lastSeen != null && nowMs - lastSeen <= DEDUPE_WINDOW_MS
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val appName = TRACKED_APPS[sbn.packageName] ?: return

        // 1-bosqich: faqat premium oilalar uchun.
        if (!isOwnerPremium) return

        // 2-bosqich: faqat bolaning bugungi eng ko'p ishlatgan (top-3)
        // ilovasidan kelgan bo'lsa.
        if (sbn.packageName !in topApps) return

        // Guruh xulosasi (GROUP_SUMMARY) va davom etayotgan (ongoing)
        // bildirishnomalarni o'tkazib yuboramiz — ular haqiqiy xabar emas.
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (title.isNotBlank() || text.isNotBlank()) {
            val analysis = RiskAnalysisEngine.analyze(title, text)
            if (analysis != null && analysis.severity != "LOW") {
                val now = System.currentTimeMillis()
                FirebaseRepo.logRiskEvent(
                    RiskEvent(
                        id = "notif_risk_${now}_${sbn.key.hashCode()}",
                        category = analysis.category,
                        severity = analysis.severity,
                        confidence = analysis.confidence,
                        packageName = sbn.packageName,
                        appName = appName,
                        source = "notification",
                        summary = analysis.summary,
                        contextText = (title + " " + text).trim().take(1200),
                        mediaType = RiskAnalysisEngine.detectMediaMarker(listOf(title, text)),
                        mediaState = if (analysis.sensitive) "HIDDEN_SENSITIVE" else "VISIBLE",
                        capturedAt = sbn.postTime,
                        evidenceAvailable = false,
                        sensitive = analysis.sensitive
                    )
                )
            }
        }

        // Ikkalasi ham bo'sh bo'lsa (masalan faqat rasm/media bildirishnomasi) — o'tkazib yuboramiz.
        if (title.isBlank() && text.isBlank()) return

        val dedupeKey = "${sbn.key}|${sbn.packageName}|$title|$text"
        if (isDuplicate(dedupeKey, System.currentTimeMillis())) return

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
