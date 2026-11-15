package uz.oilanazorati.parentcontrol.util

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Favqulodda SOS: bola tugmani BOSGANDA oxirgi joylashuvini ota-ona belgilagan
 * (2 tagacha) raqamga SMS qiladi. SMS hech biriga ketmasa, birinchi raqamga qo'ng'iroq qiladi.
 * Hech qachon o'zi avtomatik ishga tushmaydi, faqat foydalanuvchi tasdiqlagandan keyin.
 */
object SosHelper {
    private const val PREFS = "oila_nazorati"
    private const val PREF_NUMBERS = "sos_numbers"
    private const val PREF_LAST_SENT = "sos_last_sent_ms"
    private const val ACTION_SENT = "uz.oilanazorati.parentcontrol.SOS_SMS_SENT"
    private const val MIN_INTERVAL_MS = 30_000L
    private const val SMS_TIMEOUT_MS = 20_000L

    fun normalize(raw: String): String? {
        val s = raw.filter { it.isDigit() || it == '+' }
        return if (Regex("^\\+?\\d{7,15}$").matches(s)) s else null
    }

    fun cachedNumbers(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_NUMBERS, "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }

    private fun cacheNumbers(context: Context, numbers: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_NUMBERS, numbers.joinToString(",")).apply()
    }

    /** Internet bor paytda ota-ona kiritgan raqamlarni qurilmaga saqlab qo'yadi (oflayn SOS uchun). */
    fun refreshFromServer(context: Context, familyCode: String, childId: String) {
        val app = context.applicationContext
        FirebaseFirestore.getInstance()
            .collection("families").document(familyCode)
            .collection("children").document(childId)
            .get()
            .addOnSuccessListener { doc ->
                val list = (doc.get("sosNumbers") as? List<*>)
                    ?.mapNotNull { (it as? String)?.let(::normalize) }
                    ?.take(2).orEmpty()
                cacheNumbers(app, list)
            }
    }

    fun send(
        context: Context,
        childName: String,
        alertId: String? = null,
        note: String = "",
        customLat: Double? = null,
        customLng: Double? = null,
        placeLabel: String? = null,
        onDone: (String) -> Unit
    ) {
        val app = context.applicationContext
        val numbers = cachedNumbers(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_SENT, 0L) < MIN_INTERVAL_MS) {
            onDone("SOS yaqinda yuborilgan. Biroz kuting.")
            return
        }
        prefs.edit().putLong(PREF_LAST_SENT, now).apply()
        val selectedLocation = if (customLat != null && customLng != null) {
            Location("sos_custom").apply {
                latitude = customLat
                longitude = customLng
                time = System.currentTimeMillis()
            }
        } else null
        val locationCallback: (Location?) -> Unit = { location ->
            logSosAlert(app, childName, alertId, note,
                customLat ?: location?.latitude, customLng ?: location?.longitude, placeLabel,
                "SOS yordam so'rovi yuborildi.")
            if (numbers.isEmpty()) {
                onDone("SOS so'rovi internet orqali ota-onaga yuborildi. SMS raqami sozlanmagan.")
            } else if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                onDone("SOS so'rovi internet orqali yuborildi, SMS ruxsati berilmagan.")
            } else {
                val text = buildText(childName, location, note, placeLabel)
                sendToAll(app, numbers, text) { okCount ->
                    if (okCount > 0) onDone("SOS SMS yuborildi.")
                    else {
                        callFirst(app, numbers.first())
                        onDone("SMS yuborilmadi, birinchi raqamga qo'ng'iroq qilinmoqda.")
                    }
                }
            }
        }
        if (selectedLocation != null) locationCallback(selectedLocation)
        else if (!placeLabel.isNullOrBlank()) locationCallback(null)
        else fetchLocation(app, locationCallback)
    }

    private fun logSosAlert(
        context: Context, childName: String, alertId: String?, note: String,
        latitude: Double?, longitude: Double?, placeLabel: String?, message: String
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val family = prefs.getString("family_code", null) ?: return
        val child = prefs.getString("child_id", null) ?: return
        val id = alertId ?: "manual-" + java.util.UUID.randomUUID().toString()
        FirebaseFirestore.getInstance().collection("families").document(family)
            .collection("children").document(child).collection("sos_alerts")
            .document(id).set(mapOf(
                "scheduleId" to id,
                "childId" to child,
                "childName" to childName,
                "message" to note.ifBlank { message },
                "note" to note,
                "locationLabel" to placeLabel.orEmpty(),
                "latitude" to latitude,
                "longitude" to longitude,
                "mapUrl" to if (latitude != null && longitude != null) "https://maps.google.com/?q=$latitude,$longitude" else "",
                "createdAtMs" to System.currentTimeMillis(),
                "status" to "active",
                "source" to if (alertId == null) "manual" else "child_timer"
            ), com.google.firebase.firestore.SetOptions.merge())
    }

    private fun buildText(childName: String, location: Location?, note: String, placeLabel: String?): String {
        val name = childName.filter { it.code in 32..126 }.trim().take(20).ifBlank { "Bola" }
        val where = if (location != null) {
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(location.time))
            "https://maps.google.com/?q=${"%.5f".format(Locale.US, location.latitude)},${"%.5f".format(Locale.US, location.longitude)} ($time)"
        } else {
            "joylashuv aniqlanmadi"
        }
        val custom = placeLabel?.takeIf { it.isNotBlank() }?.let { " Manzil: $it." }.orEmpty()
        val detail = note.takeIf { it.isNotBlank() }?.let { " Izoh: ${it.take(65)}." }.orEmpty()
        return ("SOS! $name yordam so'rayapti.$detail$custom Joylashuv: $where").take(160)
    }

    private fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lastKnown(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        return best
    }

    private fun fetchLocation(context: Context, callback: (Location?) -> Unit) {
        if (!hasLocationPermission(context)) {
            callback(null)
            return
        }
        runCatching {
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { callback(it ?: lastKnown(context)) }
                .addOnFailureListener { callback(lastKnown(context)) }
        }.onFailure { callback(lastKnown(context)) }
    }

    private fun sendToAll(context: Context, numbers: List<String>, text: String, onFinished: (Int) -> Unit) {
        val manager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else
            @Suppress("DEPRECATION") SmsManager.getDefault()
        val okCount = AtomicInteger(0)
        val replies = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        lateinit var receiver: BroadcastReceiver

        fun finish() {
            if (finished.compareAndSet(false, true)) {
                runCatching { context.unregisterReceiver(receiver) }
                onFinished(okCount.get())
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (resultCode == Activity.RESULT_OK) okCount.incrementAndGet()
                if (replies.incrementAndGet() >= numbers.size) finish()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed({ finish() }, SMS_TIMEOUT_MS)

        numbers.forEachIndexed { index, number ->
            val sent = PendingIntent.getBroadcast(
                context, index, Intent(ACTION_SENT).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            runCatching { manager.sendTextMessage(number, null, text, sent, null) }
                .onFailure { if (replies.incrementAndGet() >= numbers.size) finish() }
        }
    }

    private fun callFirst(context: Context, number: String) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
