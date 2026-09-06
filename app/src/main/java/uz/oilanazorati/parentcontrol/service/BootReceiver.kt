package uz.oilanazorati.parentcontrol.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs: SharedPreferences = context.getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        val isChildDevice = prefs.getBoolean("is_child_device", false)
        if (!isChildDevice) return // faqat "bola" sifatida sozlangan qurilmada ishga tushadi

        val svcIntent = Intent(context, MonitorForegroundService::class.java)
        ContextCompat.startForegroundService(context, svcIntent)
    }
}
