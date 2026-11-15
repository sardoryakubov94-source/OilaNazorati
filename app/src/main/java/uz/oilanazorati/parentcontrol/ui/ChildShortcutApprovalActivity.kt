package uz.oilanazorati.parentcontrol.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import uz.oilanazorati.parentcontrol.R

/**
 * Child-device confirmation screen for a parent-requested home-screen shortcut.
 * Android's launcher still shows its own system confirmation before pinning.
 */
class ChildShortcutApprovalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("oila_nazorati", MODE_PRIVATE)
        if (!prefs.getBoolean("pending_shortcut_request", false)) {
            finish()
            return
        }

        val label = prefs.getString("pending_shortcut_label", "").orEmpty().take(18)
        if (label.isBlank()) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Bosh ekranga yorliq qo'shish")
            .setMessage(
                "Ota-ona bosh ekranga yangi yorliq qo'shishni so'radi. " +
                    "Yorliq Bulut servis oilaviy xavfsizlik ilovasini ochadi.\n\n" +
                    "Ko'rinadigan nom: Bulut: $label\n\n" +
                    "Davom etsangiz, Android ishga tushirgichi qo'shishni alohida tasdiqlashingizni so'raydi."
            )
            .setPositiveButton("Davom etish") { _, _ -> requestPinShortcut(label, prefs) }
            .setNegativeButton("Rad etish") { _, _ ->
                prefs.edit().putBoolean("pending_shortcut_request", false).apply()
                finish()
            }
            .setOnCancelListener {
                prefs.edit().putBoolean("pending_shortcut_request", false).apply()
                finish()
            }
            .show()
    }

    private fun requestPinShortcut(
        label: String,
        prefs: android.content.SharedPreferences
    ) {
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            Toast.makeText(this, "Bu qurilma bosh ekranga yorliq qo'shishni qo'llab-quvvatlamaydi.", Toast.LENGTH_LONG).show()
            prefs.edit().putBoolean("pending_shortcut_request", false).apply()
            finish()
            return
        }

        val icon = buildShortcutIcon(prefs)
        val longLabel = ("Bulut: " + label).take(25)
        val shortLabel = ("Bulut " + label).take(10)
        val launchIntent = Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java)
        val shortcut = ShortcutInfo.Builder(this, "bulut_parent_custom_shortcut")
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(icon)
            .setActivity(ComponentName(this, MainActivity::class.java))
            .setIntent(launchIntent)
            .build()

        val accepted = try {
            manager.requestPinShortcut(shortcut, null)
        } catch (e: Exception) {
            false
        }
        if (accepted) {
            Toast.makeText(this, "Android oynasida bosh ekranga qo'shishni tasdiqlang.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Yorliq so'rovini yuborib bo'lmadi.", Toast.LENGTH_LONG).show()
        }
        prefs.edit().putBoolean("pending_shortcut_request", false).apply()
        finish()
    }

    private fun buildShortcutIcon(prefs: android.content.SharedPreferences): Icon {
        val key = prefs.getString("pending_shortcut_icon_key", "cloud")
        if (key == "photo") {
            val encoded = prefs.getString("pending_shortcut_icon_base64", null).orEmpty()
            val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { byteArrayOf() }
            val photo = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (photo != null) return Icon.createWithBitmap(addBulutBrandMark(photo))
        }
        val resource = when (key) {
            "geometric" -> R.drawable.ic_launcher_geometric_neutral
            "default" -> R.mipmap.ic_launcher
            else -> R.drawable.ic_launcher_cloud_neutral
        }
        return Icon.createWithResource(this, resource)
    }

    private fun addBulutBrandMark(source: Bitmap): Bitmap {
        val size = 128
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val scaled = Bitmap.createScaledBitmap(source, size, size, true)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        val badge = RectF(82f, 82f, 126f, 126f)
        paint.color = Color.rgb(30, 90, 180)
        canvas.drawOval(badge, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 28f
        paint.isFakeBoldText = true
        canvas.drawText("B", 104f, 113f, paint)
        if (scaled !== source) scaled.recycle()
        return output
    }
}
