package uz.oilanazorati.parentcontrol.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.AppUsageEvent
import uz.oilanazorati.parentcontrol.model.LocationEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.ContactSyncHelper

/**
 * Doimiy fon xizmati. Asosiy vazifalari:
 *  1) Har LOCATION_INTERVAL_MS'da bir marta joylashuvni Firestore'ga yozadi
 *  2) Har USAGE_POLL_INTERVAL_MS'da UsageStatsManager orqali qaysi ilova
 *     qachon old planga chiqib/tushganini o'qib, sessiya sifatida yozadi
 *  3) CallLogObserver orqali tizimning o'z "Qo'ng'iroqlar tarixi"
 *     jadvalidagi o'zgarishlarni kuzatib, yangi qo'ng'iroqlarni
 *     (turi, davomiyligi bilan birga) Firestore'ga yozadi
 *  4) SmsSentObserver orqali `content://sms` jadvalidagi yuborilgan
 *     xabarlarni kuzatadi (kiruvchi SMS esa SmsReceiver orqali darhol
 *     ushlanadi)
 *  5) Saqlangan kontaktlarni (faqat ism + anonim rang-hash, RAQAMSIZ)
 *     AVTOMATIK sinxronlaydi: xizmat ishga tushganda, har
 *     CONTACTS_RESYNC_INTERVAL_MS'da bir marta, VA foydalanuvchi yangi
 *     kontakt qo'shgan/o'zgartirgan zahoti (ContentObserver orqali) —
 *     ota-ona qo'lda tugma bosishi shart emas.
 *
 * MUHIM: UsageStatsManager faqat QAYSI ILOVA QACHON OCHIQ BO'LGANI (paket
 * nomi + vaqt)ni beradi — ilova ICHIDAGI harakatlar, yozilgan matn yoki
 * ko'rilgan kontent haqida HECH QANDAY ma'lumot bermaydi va bera olmaydi.
 */
class MonitorForegroundService : Service() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastUsageQueryMs = System.currentTimeMillis() - 60_000
    private var contactsObserver: ContentObserver? = null
    private var smsSentObserver: ContentObserver? = null
    private var callLogObserver: ContentObserver? = null
    private var liveTrackingListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var liveTrackingUntilMs = 0L
    private var liveTrackingLoopRunning = false

    // Joylashuv filtri uchun oxirgi QABUL QILINGAN (rad etilmagan) nuqta.
    // SharedPreferences'da saqlanadi — xizmat qayta ishga tushganda ham
    // (masalan tizim uni o'chirib qayta ko'targanda) filtr davom etaveradi.
    private val locationPrefs by lazy { getSharedPreferences("location_filter", Context.MODE_PRIVATE) }

    companion object {
        const val CHANNEL_ID = "oila_nazorati_monitor"
        const val NOTIF_ID = 1
        const val LOCATION_PROMPT_CHANNEL_ID = "oila_nazorati_location_prompt"
        const val LOCATION_PROMPT_NOTIF_ID = 2

        // GPS "sakrash" filtri sozlamalari (acceptOrRejectLocation)
        private const val MIN_SUSPICIOUS_JUMP_METERS = 500f
        private const val MAX_PLAUSIBLE_SPEED_KMH = 200.0
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LNG = "last_lng"
        private const val KEY_LAST_TIME_MS = "last_time_ms"
        const val LOCATION_INTERVAL_MS = 30 * 60 * 1000L // 30 daqiqada bir
        const val LIVE_LOCATION_INTERVAL_MS = 8 * 1000L // Jonli kuzatishda 8 soniyada bir
        const val USAGE_POLL_INTERVAL_MS = 2 * 60 * 1000L // 2 daqiqada bir
        const val CONTACTS_RESYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 soatda bir (zaxira sifatida)
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForeground(NOTIF_ID, buildNotification())
        registerCallLogObserver()
        registerContactsObserver()
        registerSmsSentObserver()
        registerLiveTrackingListener()
        schedulePeriodicWork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- Bildirishnoma (foreground service uchun majburiy) ----------------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Google xizmati", NotificationManager.IMPORTANCE_MIN
            )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google cervis")
            // MUHIM: contentText YO'Q (avval bo'sh joy " " qo'yilgan edi —
            // bu ikkinchi bo'sh qatorni band qilib, bildirishnomani
            // kerakidan balandroq/kattaroq ko'rsatib turardi). Endi faqat
            // sarlavha bitta qator sifatida ko'rinadi — pastdagi ob-havo
            // bildirishnomasi kabi ixcham.
            .setSmallIcon(R.drawable.ic_blank)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    // ---------------- Jonli kuzatish (ota-ona so'rovi bo'yicha) ----------------

    /**
     * Ota-ona LocationHistoryActivity'da "Jonli kuzatish"ni yoqsa,
     * Firestore'dagi `liveTrackingUntilMs` maydoni yangilanadi — buni
     * shu yerda real vaqtda tinglab turamiz. Muddat hali o'tmagan bo'lsa
     * va tez tsikl hali ishlamayotgan bo'lsa, uni boshlaymiz.
     */
    private fun registerLiveTrackingListener() {
        liveTrackingListener = FirebaseRepo.listenLiveTrackingFlag { untilMs ->
            liveTrackingUntilMs = untilMs
            if (untilMs > System.currentTimeMillis() && !liveTrackingLoopRunning) {
                startLiveTrackingLoop()
            }
        }
    }

    /**
     * Har LIVE_LOCATION_INTERVAL_MS'da (30 daqiqa emas, 8 soniyada bir)
     * joylashuvni yozadi — `liveTrackingUntilMs` muddati o'tguncha.
     * Muddat tugagach avtomatik to'xtaydi va oddiy 30 daqiqalik
     * jadval o'z holicha davom etaveradi (u hech qachon to'xtatilmagan).
     */
    private fun startLiveTrackingLoop() {
        liveTrackingLoopRunning = true
        handler.post(object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= liveTrackingUntilMs) {
                    liveTrackingLoopRunning = false
                    return
                }
                requestLocationOnce()
                handler.postDelayed(this, LIVE_LOCATION_INTERVAL_MS)
            }
        })
    }

    // ---------------- Davriy ishlar ----------------

    private fun schedulePeriodicWork() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                pollAppUsage()
                handler.postDelayed(this, USAGE_POLL_INTERVAL_MS)
            }
        }, USAGE_POLL_INTERVAL_MS)

        handler.postDelayed(object : Runnable {
            override fun run() {
                requestLocationOnce()
                handler.postDelayed(this, LOCATION_INTERVAL_MS)
            }
        }, LOCATION_INTERVAL_MS)

        // Xizmat ishga tushganda darhol bir marta ham bajaramiz
        requestLocationOnce()
        pollAppUsage()
        syncContactsIfPermitted()

        handler.postDelayed(object : Runnable {
            override fun run() {
                syncContactsIfPermitted()
                handler.postDelayed(this, CONTACTS_RESYNC_INTERVAL_MS)
            }
        }, CONTACTS_RESYNC_INTERVAL_MS)
    }

    // ---------------- Kontaktlarni avtomatik sinxronlash ----------------

    private fun syncContactsIfPermitted() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) ContactSyncHelper.syncNow(applicationContext)
    }

    /**
     * Manzillar kitobiga o'zgarish (yangi kontakt qo'shilishi, ismi
     * o'zgartirilishi, o'chirilishi) kuzatiladi va shu zahoti qayta
     * sinxronlanadi — ota-ona qo'lda tugma bosishi shart emas.
     */
    private fun registerContactsObserver() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                syncContactsIfPermitted()
            }
        }
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true, observer
        )
        contactsObserver = observer
    }

    // ---------------- Yuborilgan SMS'larni kuzatish (default rolsiz) ----------------

    private fun registerSmsSentObserver() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val observer = SmsSentObserver(applicationContext, handler)
        contentResolver.registerContentObserver(
            android.provider.Telephony.Sms.CONTENT_URI, true, observer
        )
        smsSentObserver = observer
    }

    // ---------------- Lokatsiya ----------------

    private fun requestLocationOnce() {
        // Joylashuv xizmatlari (GPS/tarmoq) butunlay o'chirilgan bo'lsa,
        // FusedLocationProviderClient hech qanday natija bermaydi —
        // buni ATAYLAB tekshirib, foydalanuvchiga (bir marta bosish
        // bilan) yoqish imkonini beruvchi bildirishnoma ko'rsatamiz.
        //
        // MUHIM CHEKLOV: Android xavfsizlik siyosati oddiy ilovaga
        // joylashuvni SEZILMASDAN, o'zi yoqishga umuman ruxsat
        // bermaydi — buni faqat qurilma egasining o'zi (yoki "Device
        // Owner" korporativ rejimi, bu esa butunlay boshqa, katta
        // jarayon) amalga oshira oladi. Shu sabab bu yerda eng yaxshi
        // qila oladigan narsamiz — bitta bosish bilan yoqiladigan
        // tizim so'rovini chiqarish, to'liq avtomatik emas.
        if (!isLocationServiceEnabled()) {
            showEnableLocationPrompt()
            return
        }

        try {
            val request = CurrentLocationRequest.Builder()
                // HIGH_ACCURACY — GPS sputniklaridan to'g'ridan-to'g'ri signal
                // oladi, tarmoq va Wi-Fi faqat yordamchi sifatida ishlatiladi.
                // Aniqlik: odatda 3-10 metr (ochiq osmon ostida).
                // Batareya: BALANCED dan ~2-3x ko'proq yeydi, lekin faqat
                // har 30 daqiqada bir marta qisqa muddatli so'rov bo'lgani
                // uchun umumiy ta'sir amalda unchalik katta emas.
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0) // Doim yangi o'lchov olish, kesh emas
                .build()
            fusedLocationClient.getCurrentLocation(request, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        acceptOrRejectLocation(loc.latitude, loc.longitude)
                    } else {
                        // Birinchi urinishda null keldi — so'nggi ma'lum joylashuvni
                        // olamiz (GPS va tarmoq ikkalasi ham javob bermasa, hech
                        // bo'lmaganda oldingi manzilni qayta yozamiz, sana yangi).
                        fusedLocationClient.lastLocation.addOnSuccessListener { last: Location? ->
                            if (last != null) {
                                acceptOrRejectLocation(last.latitude, last.longitude)
                            }
                        }
                    }
                }
        } catch (e: SecurityException) {
            // Lokatsiya ruxsati berilmagan — jim o'tkazib yuboramiz
        }
    }

    /**
     * GPS "sakrash" filtri: oldingi qabul qilingan nuqtadan MASOFA 500
     * metrdan katta VA shu masofani bosib o'tish uchun kerak bo'ladigan
     * TEZLIK mantiqsiz (real hayotda bo'lishi mumkin bo'lmagan) darajada
     * baland bo'lsa — bu nuqta chiqindi (GPS xatosi/sakrash) deb hisoblab,
     * Firestore'ga YOZILMAYDI va "oxirgi qabul qilingan nuqta" ham
     * yangilanmaydi (keyingi o'lchov shu eski, ishonchli nuqta bilan
     * solishtiriladi). Birinchi o'lchov (oldingi nuqta hali yo'q bo'lsa)
     * har doim qabul qilinadi.
     */
    private fun acceptOrRejectLocation(lat: Double, lng: Double) {
        val nowMs = System.currentTimeMillis()
        val prevLatStr = locationPrefs.getString(KEY_LAST_LAT, null)
        val prevLngStr = locationPrefs.getString(KEY_LAST_LNG, null)
        val prevTimeMs = locationPrefs.getLong(KEY_LAST_TIME_MS, 0L)

        if (prevLatStr != null && prevLngStr != null && prevTimeMs > 0) {
            val prevLat = prevLatStr.toDoubleOrNull()
            val prevLng = prevLngStr.toDoubleOrNull()
            if (prevLat != null && prevLng != null) {
                val distanceMeters = FloatArray(1)
                Location.distanceBetween(prevLat, prevLng, lat, lng, distanceMeters)
                val elapsedSeconds = (nowMs - prevTimeMs) / 1000.0
                if (distanceMeters[0] > MIN_SUSPICIOUS_JUMP_METERS && elapsedSeconds > 0) {
                    val speedKmh = (distanceMeters[0] / elapsedSeconds) * 3.6
                    if (speedKmh > MAX_PLAUSIBLE_SPEED_KMH) {
                        // Sakrash + mantiqsiz tezlik — GPS xatosi, e'tiborsiz qoldiramiz.
                        return
                    }
                }
            }
        }

        FirebaseRepo.logLocation(LocationEvent(lat = lat, lng = lng, vaqtMs = nowMs))
        locationPrefs.edit()
            .putString(KEY_LAST_LAT, lat.toString())
            .putString(KEY_LAST_LNG, lng.toString())
            .putLong(KEY_LAST_TIME_MS, nowMs)
            .apply()
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
        // GPS YOKI tarmoq (Network) joylashuvi yoqilgan bo'lsa — yetarli.
        // Faqat ikkalasi birdan o'chirilganda (qurilma "joylashuv" tumblerini
        // butunlay o'chirganda) false qaytaradi va foydalanuvchiga xabar yuboriladi.
        return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.PASSIVE_PROVIDER)
    }

    private fun showEnableLocationPrompt() {
        val intent = Intent(this, uz.oilanazorati.parentcontrol.ui.LocationPromptActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, LOCATION_PROMPT_CHANNEL_ID)
            .setContentTitle("Google cervis")
            .setContentText("Joylashuv xizmatini yoqish uchun bosing")
            .setSmallIcon(R.drawable.ic_blank)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LOCATION_PROMPT_CHANNEL_ID, "Joylashuv eslatmasi", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        getSystemService(NotificationManager::class.java).notify(LOCATION_PROMPT_NOTIF_ID, notification)
    }

    // ---------------- Ilova ishlatilishi ----------------

    /**
     * UsageEvents orqali oxirgi so'rovdan beri sodir bo'lgan
     * MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND hodisalarini o'qib,
     * har bir "sessiya"ni (ilova ochilgan — yopilgan) hisoblab chiqadi.
     */
    private fun pollAppUsage() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastUsageQueryMs, now)

        val openTimestamps = HashMap<String, Long>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    openTimestamps[event.packageName] = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = openTimestamps.remove(event.packageName)
                    if (start != null) {
                        val durationSec = ((event.timeStamp - start) / 1000).coerceAtLeast(0)
                        if (durationSec >= 3) { // 3 soniyadan qisqa "tasodifiy ochilish"larni hisobga olmaymiz
                            FirebaseRepo.logAppUsage(
                                AppUsageEvent(
                                    ilovaNomi = appLabelFor(event.packageName),
                                    paketNomi = event.packageName,
                                    boshlanishMs = start,
                                    tugashMs = event.timeStamp,
                                    davomiylikSoniya = durationSec
                                )
                            )
                        }
                    }
                }
            }
        }
        lastUsageQueryMs = now
    }

    private fun appLabelFor(packageName: String): String {
        return try {
            val pm = packageManager
            val ai: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    // ---------------- Qo'ng'iroqlar tarixini kuzatish (ishonchli usul) ----------------

    private fun registerCallLogObserver() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val observer = CallLogObserver(applicationContext, handler)
        contentResolver.registerContentObserver(
            android.provider.CallLog.Calls.CONTENT_URI, true, observer
        )
        callLogObserver = observer
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        contactsObserver?.let { contentResolver.unregisterContentObserver(it) }
        smsSentObserver?.let { contentResolver.unregisterContentObserver(it) }
        callLogObserver?.let { contentResolver.unregisterContentObserver(it) }
        liveTrackingListener?.remove()
    }
}
