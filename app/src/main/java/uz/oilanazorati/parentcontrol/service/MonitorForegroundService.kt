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
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.AppUsageEvent
import uz.oilanazorati.parentcontrol.model.LocationEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.ContactSyncHelper
import java.util.concurrent.Executors

/**
 * Doimiy fon xizmati. To'rtta vazifasi bor:
 *  1) Har LOCATION_INTERVAL_MS'da bir marta joylashuvni Firestore'ga yozadi
 *  2) Har USAGE_POLL_INTERVAL_MS'da UsageStatsManager orqali qaysi ilova
 *     qachon old planga chiqib/tushganini o'qib, sessiya sifatida yozadi
 *  3) TelephonyCallback orqali qo'ng'iroq holatini kuzatib, tugagach
 *     CallSessionTracker.onCallEnded() ni chaqiradi
 *  4) Saqlangan kontaktlarni (faqat ism + anonim rang-hash, RAQAMSIZ)
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

    companion object {
        const val CHANNEL_ID = "oila_nazorati_monitor"
        const val NOTIF_ID = 1
        const val LOCATION_INTERVAL_MS = 30 * 60 * 1000L // 30 daqiqada bir
        const val USAGE_POLL_INTERVAL_MS = 2 * 60 * 1000L // 2 daqiqada bir
        const val CONTACTS_RESYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 soatda bir (zaxira sifatida)
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForeground(NOTIF_ID, buildNotification())
        registerCallStateListener()
        registerContactsObserver()
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
                CHANNEL_ID, "Oila nazorati", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Oila nazorati ishlamoqda")
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
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

    // ---------------- Lokatsiya ----------------

    private fun requestLocationOnce() {
        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .build()
            fusedLocationClient.getCurrentLocation(request, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        FirebaseRepo.logLocation(
                            LocationEvent(lat = loc.latitude, lng = loc.longitude, vaqtMs = System.currentTimeMillis())
                        )
                    }
                }
        } catch (e: SecurityException) {
            // Lokatsiya ruxsati berilmagan — jim o'tkazib yuboramiz
        }
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

    // ---------------- Qo'ng'iroq holati (davomiylikni aniqlash uchun) ----------------

    private fun registerCallStateListener() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tm.registerTelephonyCallback(
                Executors.newSingleThreadExecutor(),
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state)
                    }
                }
            )
        }
        // API < 31 uchun PhoneStateListener asosidagi eski usul kerak bo'lsa
        // shu yerga qo'shiladi (qisqartirish uchun bu namunada o'tkazib yuborildi).
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> CallSessionTracker.onCallAnswered()
            TelephonyManager.CALL_STATE_IDLE -> CallSessionTracker.onCallEnded()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        contactsObserver?.let { contentResolver.unregisterContentObserver(it) }
    }
}
