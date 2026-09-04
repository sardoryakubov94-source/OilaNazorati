package uz.oilanazorati.parentcontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.ui.ScreenCaptureConsentActivity

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        restoreSavedPairing(this)
        applySavedTheme(this)
        remindChildAboutScreenCaptureConsent(this)
    }

    companion object {
        fun restoreSavedPairing(context: Context) {
            val prefs = context.getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            // Har safar process qayta ishga tushganda tanlangan oila kodi va
            // farzand ID'sini diskdagi qiymatdan tiklaymiz. Theme almashtirilishi
            // activity/process qayta yaratilganida pairing yo'qolib qolmasligi kerak.
            val savedCode = prefs.getString("family_code", null)
            val savedChildId = prefs.getString("child_id", null)
            if (!savedCode.isNullOrBlank()) FirebaseRepo.familyCode = savedCode
            if (!savedChildId.isNullOrBlank()) FirebaseRepo.childId = savedChildId
        }

        fun applySavedTheme(context: Context) {
            val prefs = context.getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            AppCompatDelegate.setDefaultNightMode(
                if (prefs.getBoolean("light_theme", false)) AppCompatDelegate.MODE_NIGHT_NO
                else AppCompatDelegate.MODE_NIGHT_YES
            )
        }

        private fun remindChildAboutScreenCaptureConsent(context: Context) {
            val prefs = context.getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("is_child_device", false) || prefs.getBoolean("screen_capture_consented", false)) return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("oila_nazorati_screen_setup", "Ekran nazorati sozlamasi", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val intent = Intent(context, ScreenCaptureConsentActivity::class.java)
            val pending = PendingIntent.getActivity(context, 902, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(NotificationManager::class.java).notify(
                902,
                NotificationCompat.Builder(context, "oila_nazorati_screen_setup")
                    .setSmallIcon(R.drawable.ic_blank)
                    .setContentTitle("Oila Nazorati — ekran nazorati")
                    .setContentText("Ekran nazoratini yoqish uchun bir martalik Android ruxsatini bering")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
