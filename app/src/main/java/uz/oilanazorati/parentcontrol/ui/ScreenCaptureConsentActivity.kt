package uz.oilanazorati.parentcontrol.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.screenshot.ScreenCaptureService

class ScreenCaptureConsentActivity : Activity() {
    private val requestCode = 901

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        box.addView(TextView(this).apply {
            text = "📸 Ekran nazorati\n\nAndroid ekranni ulashish ruxsatini ko'rsatadi. Ruxsat berilgandan keyin Oila Nazorati ota-ona sozlamalaridagi chastota bo'yicha ekran tasvirlarini olishi mumkin. Ruxsatni istalgan vaqtda to'xtatishingiz mumkin."
            textSize = 18f
        })
        box.addView(Button(this).apply {
            text = "Ekran tasviriga ruxsat berish"
            setOnClickListener {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), requestCode)
            }
        })
        box.addView(Button(this).apply {
            text = "Bekor qilish"
            setOnClickListener { finish() }
        })
        setContentView(box)
    }

    @Deprecated("Activity Result API migration is not required for this small legacy-compatible consent screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != this.requestCode || resultCode != RESULT_OK || data == null) return
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        getSharedPreferences("oila_nazorati", MODE_PRIVATE).edit().putBoolean("screen_capture_consented", true).apply()
        finish()
    }
}
