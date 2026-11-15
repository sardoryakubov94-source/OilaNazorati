package uz.oilanazorati.parentcontrol.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.firestore.FirebaseFirestore
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.ui.SosPromptActivity
import uz.oilanazorati.parentcontrol.util.SosHelper
import uz.oilanazorati.parentcontrol.util.SosTimerManager

class SosAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val id = intent.getStringExtra("scheduleId") ?: return
        val (savedId, family, child) = SosTimerManager.savedSchedule(context)
        if (savedId != id) return
        when (action) {
            ACTION_PROMPT -> showPrompt(context, id)
            ACTION_AUTO_SEND -> {
                SosTimerManager.markAutoStarted(context)
                val name = SosTimerManager.savedChildName(context)
                val location = SosTimerManager.savedLocation(context)
                SosHelper.send(context, name, id, SosTimerManager.savedNote(context), location.first, location.second, location.third) { result ->
                    if (result == "SOS SMS yuborildi." || result.startsWith("SMS yuborilmadi")) {
                        SosTimerManager.markStatus(context, "auto_sent")
                    }
                    SosTimerManager.clearCurrent(context)
                    showSentNotification(context, result)
                }
            }
        }
    }

    private fun showPrompt(context: Context, id: String) {
        createChannel(context)
        val open = Intent(context, SosPromptActivity::class.java).putExtra("scheduleId", id)
        val pending = PendingIntent.getActivity(context, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("SOS yuborilsinmi?")
            .setContentText("Ota-onangizga yordam so'rovini yuborish kerakmi? 1 daqiqada javob bo'lmasa, SOS avtomatik yuboriladi.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Ota-onangizga SOS xabarini yuboraymi? Javob berish uchun bosing. 1 daqiqada javob bo'lmasa, SOS avtomatik yuboriladi."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        SosTimerManager.scheduleAutoSend(context, id)
    }

    private fun showSentNotification(context: Context, result: String) {
        createChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("SOS yuborildi")
            .setContentText(result)
            .setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + 1, n) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "SOS ogohlantirishlari", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_PROMPT = "uz.oilanazorati.parentcontrol.SOS_PROMPT"
        const val ACTION_AUTO_SEND = "uz.oilanazorati.parentcontrol.SOS_AUTO_SEND"
        private const val CHANNEL = "sos_alerts"
        private const val NOTIFICATION_ID = 8041
    }
}
