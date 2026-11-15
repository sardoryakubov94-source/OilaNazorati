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

    fun start(context: Context, familyCode: String, childId: String, childName: String, delayMinutes: Long, onSaved: (Boolean, String) -> Unit = { _, _ -> }) {
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
            .putString(KEY_NAME, childName).apply()

        scheduleAlarm(app, id, dueAt, SosAlarmReceiver.ACTION_PROMPT)
        val data = hashMapOf<String, Any>(
            "scheduleId" to id,
            "childId" to childId,
            "childName" to childName,
            "dueAtMs" to dueAt,
            "serverNotifyAtMs" to notifyAt,
            "createdAtMs" to System.currentTimeMillis(),
            "status" to "scheduled"
        )
        FirebaseFirestore.getInstance().collection("families").document(familyCode)
            .collection("children").document(childId).collection("sos_schedules")
            .document(id).set(data)
            .addOnSuccessListener { onSaved(true, "SOS taymeri yoqildi.") }
            .addOnFailureListener { error ->
                // Firestore offline persistence queues writes; local alarm remains active.
                onSaved(true, "Taymer telefonda yoqildi. Server bilan sinxronlash internet qaytganda amalga oshadi.")
            }
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
        prefs.edit().remove(KEY_ID).remove(KEY_DUE).apply()
        if (family.isNullOrBlank() || child.isNullOrBlank()) {
            onDone(true, "Taymer bekor qilindi.")
            return
        }
        FirebaseFirestore.getInstance().collection("families").document(family)
            .collection("children").document(child).collection("sos_schedules")
            .document(id).update("status", "cancelled", "cancelledAtMs", System.currentTimeMillis())
            .addOnSuccessListener { onDone(true, "Taymer bekor qilindi.") }
            .addOnFailureListener { onDone(true, "Taymer telefonda bekor qilindi. Serverga bekor qilish buyrug'i internet qaytganda yuboriladi.") }
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null) ?: return
        val due = prefs.getLong(KEY_DUE, 0L)
        if (due <= 0L) return
        if (due <= System.currentTimeMillis()) {
            scheduleAlarm(context, id, System.currentTimeMillis() + 1000L, SosAlarmReceiver.ACTION_PROMPT)
        } else {
            scheduleAlarm(context, id, due, SosAlarmReceiver.ACTION_PROMPT)
        }
    }

    internal fun savedChildName(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null) ?: "Bola"

    internal fun savedSchedule(context: Context): Triple<String?, String?, String?> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(p.getString(KEY_ID, null), p.getString(KEY_FAMILY, null), p.getString(KEY_CHILD, null))
    }

    internal fun markStatus(context: Context, status: String) {
        val (id, family, child) = savedSchedule(context)
        if (id.isNullOrBlank() || family.isNullOrBlank() || child.isNullOrBlank()) return
        FirebaseFirestore.getInstance().collection("families").document(family)
            .collection("children").document(child).collection("sos_schedules")
            .document(id).update("status", status, "updatedAtMs", System.currentTimeMillis())
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
