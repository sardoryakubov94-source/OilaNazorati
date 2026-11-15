package uz.oilanazorati.parentcontrol.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.ui.ParentDashboardActivity

class SosFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("parents").document(uid)
            .update("fcmTokens", com.google.firebase.firestore.FieldValue.arrayUnion(token))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: message.notification?.title ?: "🆘 SOS — yordam so'rovi"
        val body = message.data["body"] ?: message.notification?.body ?: "Farzandingiz SOS yubordi. Oila nazorati panelini oching."
        val channelId = "parent_sos"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(channelId, "SOS xabarlari", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(this, ParentDashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(this, 991, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_app_logo).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).setContentIntent(pending).build()
        (getSystemService(NotificationManager::class.java)).notify(991, notification)
    }
}
