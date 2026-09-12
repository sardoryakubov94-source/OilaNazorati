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
import uz.oilanazorati.parentcontrol.util.SimInfoSync

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
 *     AVTOMATIK sinxronlaydi.
 *  6) Faol SIM kartalar soni, operatori va Android taqdim qilgan telefon
 *     raqamlarini ota-ona paneli uchun sinxronlaydi.
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

    private val locationPrefs by lazy { getSharedPreferences("location_filter", Context.MODE_PRIVATE) }

    companion object {
        const val CHANNEL_ID = "oila_nazorati_monitor"
        const val NOTIF_ID = 1
        const val LOCATION_PROMPT_CHANNEL_ID = "oila_nazorati_location_prompt"
        const val LOCATION_PROMPT_NOTIF_ID = 2
        private const val MIN_SUSPICIOUS_JUMP_METERS = 500f
        private const val MAX_PLAUSIBLE_SPEED_KMH = 200.0
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LNG = "last_lng"
        private const val KEY_LAST_TIME_MS = "last_time_ms"
        const val LOCATION_INTERVAL_MS = 30 * 60 * 1000L
        const val LIVE_LOCATION_INTERVAL_MS = 8 * 1000L
        // Ilova ishlatilishini kuzatish tarixi to'liq saqlanadi — faqat
        // qanchalik tez-tez tekshirilishi kamaytirilgan (batareya tejash).
        const val USAGE_POLL_INTERVAL_MS = 5 * 60 * 1000L
        const val CONTACTS_RESYNC_INTERVAL_MS = 12 * 60 * 60 * 1000L
        // Oddiy (jonli kuzatish bo'lmagan) joylashuv so'rovlari uchun: yaqinda
        // olingan joylashuv keshi bo'lsa, GPS'ni qayta ishga tushirmay o'shani
        // ishlatadi — quvvat ko'p sarflaydigan GPS so'rovlarini kamaytiradi.
        private const val NORMAL_LOCATION_MAX_AGE_MS = 5 * 60 * 1000L
    }

    private var micRequestListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForeground(NOTIF_ID, buildNotification())
        registerMicRequestListener()
        registerCallLogObserver()
        registerContactsObserver()
        registerSmsSentObserver()
        registerLiveTrackingListener()
        SimInfoSync.start(applicationContext)
        schedulePeriodicWork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Har servis qayta ishga tushganda SIM/joylashuv/foydalanish ishini
        // TAKRORLAMAYMIZ — SimInfoSync.start() (onCreate) allaqachon
        // dastlabki sinxronlashni bajaradi va SIM o'zgarishini kuzatib
        // turadi. Bu keraksiz Firestore yozuvlari va batareya sarfini
        // oldini oladi (funksional o'zgarish yo'q — faqat takroriy chaqiruv
        // olib tashlandi).
        return START_STICKY
    }

    /** Doimiy ishlab turadigan bu servisda faqat YENGIL Firestore tinglovchisi
     * saqlanadi. WebRTC/mikrofon servisi (batareya sarflaydigan qismi) faqat
     * haqiqiy so'rov (transport=webrtc, status=requested, webrtcOffer mavjud)
     * kelganda ishga tushadi — va o'sha servis o'zi bo'sh turgan payt birozdan
     * so'ng o'z-o'zini to'xtatadi (qarang: WebRtcAmbientAudioService). */
    private fun registerMicRequestListener() {
        val isChild = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            .getBoolean("is_child_device", false)
        if (!isChild) return
        val family = FirebaseRepo.familyCode ?: return
        val child = FirebaseRepo.childId ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("families").document(family).collection("children").document(child)
            .collection("mic_requests").document("current")
        micRequestListener = ref.addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            val data = snap.data.orEmpty()
            if (data["transport"] != "webrtc") return@addSnapshotListener
            val state = data["status"] as? String ?: return@addSnapshotListener
            if (state != "requested" && state != "webrtc_requested") return@addSnapshotListener
            val hasOffer = !(data["webrtcOffer"] as? String).isNullOrBlank()
            if (!hasOffer) return@addSnapshotListener
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) return@addSnapshotListener
            try {
                ContextCompat.startForegroundService(this, Intent(this, WebRtcAmbientAudioService::class.java))
            } catch (t: Throwable) {
                // Android may reject microphone FGS startup when this service was
                // itself restarted from the background/boot. A later visible app
                // launch calls onStartCommand again and retries safely.
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("webrtc_ambient_start_failed", t.javaClass.name)
                    log("WebRtcAmbientAudioService start failed: ${t.message}")
                    recordException(t)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setSmallIcon(R.drawable.ic_blank)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun registerLiveTrackingListener() {
        liveTrackingListener = FirebaseRepo.listenLiveTrackingFlag { untilMs ->
            liveTrackingUntilMs = untilMs
            if (untilMs > System.currentTimeMillis() && !liveTrackingLoopRunning) {
                startLiveTrackingLoop()
            }
            if (untilMs <= System.currentTimeMillis()) liveTrackingLoopRunning = false
        }
    }

    private fun startLiveTrackingLoop() {
        liveTrackingLoopRunning = true
        handler.post(object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= liveTrackingUntilMs) {
                    liveTrackingLoopRunning = false
                    return
                }
                // Jonli kuzatish faqat ochiq turgan payt yuqori aniqlik ishlatadi.
                requestLocationOnce(highAccuracy = true, maxAgeMs = 0L)
                handler.postDelayed(this, LIVE_LOCATION_INTERVAL_MS)
            }
        })
    }

    private fun schedulePeriodicWork() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                pollAppUsage()
                handler.postDelayed(this, USAGE_POLL_INTERVAL_MS)
            }
        }, USAGE_POLL_INTERVAL_MS)

        handler.postDelayed(object : Runnable {
            override fun run() {
                requestLocationOnce(highAccuracy = false, maxAgeMs = NORMAL_LOCATION_MAX_AGE_MS)
                handler.postDelayed(this, LOCATION_INTERVAL_MS)
            }
        }, LOCATION_INTERVAL_MS)

        requestLocationOnce(highAccuracy = false, maxAgeMs = NORMAL_LOCATION_MAX_AGE_MS)
        pollAppUsage()
        syncContactsIfPermitted()

        handler.postDelayed(object : Runnable {
            override fun run() {
                syncContactsIfPermitted()
                handler.postDelayed(this, CONTACTS_RESYNC_INTERVAL_MS)
            }
        }, CONTACTS_RESYNC_INTERVAL_MS)
    }

    private fun syncContactsIfPermitted() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) ContactSyncHelper.syncNow(applicationContext)
    }

    private fun registerContactsObserver() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { syncContactsIfPermitted() }
        }
        contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        contactsObserver = observer
    }

    private fun registerSmsSentObserver() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val observer = SmsSentObserver(applicationContext, handler)
        contentResolver.registerContentObserver(android.provider.Telephony.Sms.CONTENT_URI, true, observer)
        smsSentObserver = observer
    }

    private fun requestLocationOnce(highAccuracy: Boolean, maxAgeMs: Long) {
        if (!isLocationServiceEnabled()) {
            showEnableLocationPrompt()
            return
        }
        try {
            val priority = if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val request = CurrentLocationRequest.Builder()
                .setPriority(priority)
                .setMaxUpdateAgeMillis(maxAgeMs)
                .build()
            fusedLocationClient.getCurrentLocation(request, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) acceptOrRejectLocation(loc.latitude, loc.longitude)
                    else fusedLocationClient.lastLocation.addOnSuccessListener { last: Location? ->
                        if (last != null) acceptOrRejectLocation(last.latitude, last.longitude)
                    }
                }
        } catch (_: SecurityException) {
        }
    }

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
                    if (speedKmh > MAX_PLAUSIBLE_SPEED_KMH) return
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

    private fun pollAppUsage() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastUsageQueryMs, now)
        val openTimestamps = HashMap<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> openTimestamps[event.packageName] = event.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = openTimestamps.remove(event.packageName)
                    if (start != null) {
                        val durationSec = ((event.timeStamp - start) / 1000).coerceAtLeast(0)
                        if (durationSec >= 3) {
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

    private fun registerCallLogObserver() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val observer = CallLogObserver(applicationContext, handler)
        contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, observer)
        callLogObserver = observer
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        contactsObserver?.let { contentResolver.unregisterContentObserver(it) }
        smsSentObserver?.let { contentResolver.unregisterContentObserver(it) }
        callLogObserver?.let { contentResolver.unregisterContentObserver(it) }
        liveTrackingListener?.remove()
        micRequestListener?.remove()
    }
}
