package uz.oilanazorati.parentcontrol.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import uz.oilanazorati.parentcontrol.R

/**
 * Eng kam qo'llab-quvvatlanadigan versiyadan past bo'lgan ilova nusxalari
 * uchun to'liq bloklovchi ekran (qarang: FirebaseRepo.checkMinRequiredVersion,
 * MainActivity.onCreate). Orqaga qaytish tugmasi ekranni yopib
 * yubormaydi — foydalanuvchi yangilanmaguncha ilovaning boshqa hech
 * qanday qismiga o'ta olmaydi.
 */
class UpdateRequiredActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_required)

        findViewById<android.widget.Button>(R.id.btnUpdateNow).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://oilanazorati.uz/")))
        }
    }

    override fun onBackPressed() {
        // Ataylab hech narsa qilinmaydi — bu ekran chetlab o'tilmasligi kerak.
    }
}
