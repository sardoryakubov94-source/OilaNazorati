package uz.oilanazorati.parentcontrol.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.firestore.FirebaseFirestore
import uz.oilanazorati.parentcontrol.service.SosAlarmReceiver
import java.util.UUID

object SosTimerManager {
    private const val PREFS = "oila_nazorati"
    private const val KEY_ID = "sos_timer_id"
    private const val KEY_DUE = "sos_timer_due_ms"
    private const val KEY_FAMILY = "sos_timer_family"
    private const val KEY_CHILD = "sos_timer_child"
    private const val KEY_NAME = "sos_timer_child_name"
    private const val KEY_AUTO_STARTED = "sos_timer_auto_started"
    private const val KEY_NOTE = "sos_timer_note"
    private const val KEY_LAT = "sos_timer_lat"
    private const val KEY_LNG = "sos_timer_lng"
    private const val KEY_PLACE = "sos_timer_place"

    fun start(
        context: Context, familyCode: String, childId: String, childName: String, delayMinutes: Long,
        note: String = "", latitude: Double? = null, longitude: Double? = null, placeLabel: String? = null,
        onSaved: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (delayMinutes < 1L || delayMinutes > 30L * 24L * 60L) {
            onSaved(false, "Taymer 1 daqiqadan 30 kungacha bo'lishi kerak.")
            return
        }
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        val dueAt = System.currentTimeMillis() + delayMinutes * 60_000L
        val notifyAt = dueAt + 60_000L
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ID, id).putLong(KEY_DUE, dueAt)
            .putString(KEY_FAMILY, familyCode).putString(KEY_CHILD, childId)
            .putString(KEY_NAME, childName).putBoolean(KEY_AUTO_STARTED, false)
            .putString(KEY_NOTE, note).putString(KEY_PLACE, placeLabel.orEmpty())
            .putString(KEY_LAT, latitude?.toString()).putString(KEY_LNG, longitude?.toString()).apply()

        scheduleAlarm(app, id, dueAt, SosAlarmReceiver.ACTION_PROMPT)
        val data = hashMapOf<String, Any>(
            "scheduleId" to id,
            "childId" to childId,
            "childName" to childName,
            "note" to note,
            "locationLabel" to placeLabel.orEmpty(),
            "mapUrl" to if (latitude != null && longitude != null) "https://maps.google.com/?q=$latitude,$longitude" else "",
            "dueAtMs" to dueAt,
            "serverNotifyAtMs" to notifyAt,
            "createdAtMs" to System.currentTimeMillis(),
            "status" to "scheduled"
        )
        if (latitude != null && longitude != null) {
            data["latitude"] = latitude
            data["longitude"] = longitude
        }
        onSaved(true, "Taymer telefonda yoqildi. Server jadvali internet orqali sinxronlanadi.")
        FirebaseFirestore.getInstance().collection("families").document(familyCode)
            .collection("children").document(childId).collection("sos_schedules")
            .document(id).set(data)
            .addOnSuccessListener { onSaved(true, "SOS taymeri telefon va serverda saqlandi.") }
            .addOnFailureListener { onSaved(false, "Serverga saqlanmadi. Telefon o'chsa SOS yetib bormasligi mumkin.") }
    }

    fun cancel(context: Context, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null)
        val family = prefs.getString(KEY_FAMILY, null)
        val child = prefs.getString(KEY_CHILD, null)
        if (id.isNullOrBlank()) {
            onDone(false, "Faol SOS taymeri topilmadi.")
            return
        }
        cancelAlarm(app, id, SosAlarmReceiver.ACTION_PROMPT)
        cancelAlarm(app, id, SosAlarmReceiver.ACTION_AUTO_SEND)
        prefs.edit().remove(KEY_ID).remove(KEY_DUE).remove(KEY_NAME).remove(KEY_NOTE)
            .remove(KEY_LAT).remove(KEY_LNG).remove(KEY_PLACE).remove(KEY_AUTO_STARTED).apply()
        if (family.isNullOrBlank() || child.isNullOrBlank()) {
            onDone(true, "Taymer bekor qilindi.")
            return
        }
        onDone(true, "Taymer telefonda bekor qilindi. Serverga bekor qilish buyrug'i sinxronlanadi; internet bo'lmasa, buyrug' yetib borguncha server ogohlantirishi ishlashi mumkin.")
        FirebaseFirestore.getInstance().collection("families").document(family)
            .collection("children").document(child).collection("sos_schedules")
            .document(id).update("status", "cancelled", "cancelledAtMs", System.currentTimeMillis())
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null) ?: return
        val due = prefs.getLong(KEY_DUE, 0L)
        if (due <= 0L) return
        val now = System.currentTimeMillis()
        if (now >= due + 60_000L) {
            // Qurilma taymer muddati va 1 daqiqalik javob oynasi davomida o'chiq bo'lgan.
            scheduleAlarm(context, id, now + 1000L, SosAlarmReceiver.ACTION_AUTO_SEND)
        } else if (now >= due) {
            scheduleAlarm(context, id, now + 1000L, SosAlarmReceiver.ACTION_PROMPT)
        } else {
            scheduleAlarm(context, id, due, SosAlarmReceiver.ACTION_PROMPT)
        }
    }

    internal fun savedChildName(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null) ?: "Bola"

    internal fun savedNote(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NOTE, "").orEmpty()

    internal fun savedLocation(context: Context): Triple<Double?, Double?, String?> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(p.getString(KEY_LAT, null)?.toDoubleOrNull(), p.getString(KEY_LNG, null)?.toDoubleOrNull(),
            p.getString(KEY_PLACE, null)?.takeIf { it.isNotBlank() })
    }

    internal fun savedSchedule(context: Context): Triple<String?, String?, String?> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(p.getString(KEY_ID, null), p.getString(KEY_FAMILY, null), p.getString(KEY_CHILD, null))
    }

    internal fun markAutoStarted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_STARTED, true).apply()
    }

    internal fun isAutoStarted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_STARTED, false)

    internal fun clearCurrent(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ID).remove(KEY_DUE).remove(KEY_FAMILY).remove(KEY_CHILD).remove(KEY_NAME)
            .remove(KEY_AUTO_STARTED).remove(KEY_NOTE).remove(KEY_LAT).remove(KEY_LNG).remove(KEY_PLACE).apply()
    }

    internal fun markStatus(context: Context, status: String) {
        val (id, family, child) = savedSchedule(context)
        if (id.isNullOrBlank() || family.isNullOrBlank() || child.isNullOrBlank()) return
        FirebaseFirestore.getInstance().collection("families").document(family)
            .collection("children").document(child).collection("sos_schedules")
            .document(id).update("status", status, "updatedAtMs", System.currentTimeMillis())
    }

    internal fun scheduleAutoSend(context: Context, id: String) {
        scheduleAlarm(context, id, System.currentTimeMillis() + 60_000L, uz.oilanazorati.parentcontrol.service.SosAlarmReceiver.ACTION_AUTO_SEND)
    }

    internal fun cancelAutoSend(context: Context, id: String) {
        cancelAlarm(context, id, uz.oilanazorati.parentcontrol.service.SosAlarmReceiver.ACTION_AUTO_SEND)
    }

    private fun scheduleAlarm(context: Context, id: String, atMs: Long, action: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SosAlarmReceiver::class.java).setAction(action).putExtra("scheduleId", id)
        val request = (id + action).hashCode()
        val pending = PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
        } else alarm.setExact(AlarmManager.RTC_WAKEUP, atMs, pending)
    }

    private fun cancelAlarm(context: Context, id: String, action: String) {
        val intent = Intent(context, SosAlarmReceiver::class.java).setAction(action).putExtra("scheduleId", id)
        val pending = PendingIntent.getBroadcast(context, (id + action).hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        pending?.let {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it)
            it.cancel()
        }
    }
}
