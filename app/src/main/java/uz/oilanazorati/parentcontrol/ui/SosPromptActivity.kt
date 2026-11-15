package uz.oilanazorati.parentcontrol.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import uz.oilanazorati.parentcontrol.util.SosHelper
import uz.oilanazorati.parentcontrol.util.SosTimerManager

class SosPromptActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("scheduleId")
        val (savedId, family, child) = SosTimerManager.savedSchedule(this)
        if (id.isNullOrBlank() || id != savedId) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("🆘 SOS yuboraymi?")
            .setMessage("Ota-onangizga yordam so'rovini yuboraymi? Javob bermasangiz, 1 daqiqadan so'ng SOS avtomatik yuboriladi.")
            .setPositiveButton("Ha, yuborish") { _, _ ->
                SosTimerManager.cancelAutoSend(this, id)
                if (SosTimerManager.savedSchedule(this).first != id || SosTimerManager.isAutoStarted(this)) {
                    finish()
                    return@setPositiveButton
                }
                SosHelper.send(this, SosTimerManager.savedChildName(this), id) { result ->
                    runOnUiThread {
                        if (result == "SOS SMS yuborildi." || result.startsWith("SMS yuborilmadi")) {
                            SosTimerManager.markStatus(this, "child_confirmed")
                        }
                        SosTimerManager.clearCurrent(this)
                        finish()
                    }
                }
            }
             .setNegativeButton("Yo'q, bekor qilish") { _, _ ->
                SosTimerManager.cancelAutoSend(this, id)
                if (SosTimerManager.savedSchedule(this).first != id || SosTimerManager.isAutoStarted(this)) {
                    android.widget.Toast.makeText(this, "SOS yuborish jarayoni boshlangan; uni endi bekor qilib bo'lmaydi.", android.widget.Toast.LENGTH_LONG).show()
                    finish()
                    return@setNegativeButton
                }
                SosTimerManager.markStatus(this, "cancelled")
                SosTimerManager.clearCurrent(this)
                android.widget.Toast.makeText(this, "Taymer telefonda bekor qilindi. Internet bo'lmasa, bekor qilish serverga kechikib yetishi mumkin.", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
            .setOnCancelListener { }
            .show()
    }
}
