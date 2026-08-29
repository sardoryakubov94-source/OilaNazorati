package uz.oilanazorati.parentcontrol.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Ko'rinmas "trampolin" activity. MonitorForegroundService joylashuv
 * xizmatlari o'chirilganini payqasa, bildirishnoma chiqaradi; foydalanuvchi
 * o'sha bildirishnomani bosganda shu activity ochiladi va darhol qurilma
 * joylashuv sozlamalari ekraniga yo'naltirib, o'zi yopiladi.
 */
class LocationPromptActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        finish()
    }
}
