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
                if (SosTimerManager.savedSchedule(this).first != id) {
                    finish()
                    return@setPositiveButton
                }
                SosTimerManager.markStatus(this, "child_confirmed")
                SosHelper.send(this, SosTimerManager.savedChildName(this)) { }
                SosTimerManager.clearCurrent(this)
                finish()
            }
             .setNegativeButton("Yo'q, bekor qilish") { _, _ ->
                SosTimerManager.cancelAutoSend(this, id)
                SosTimerManager.markStatus(this, "cancelled")
                SosTimerManager.clearCurrent(this)
                finish()
            }
            .setOnCancelListener { }
            .show()
    }
}
