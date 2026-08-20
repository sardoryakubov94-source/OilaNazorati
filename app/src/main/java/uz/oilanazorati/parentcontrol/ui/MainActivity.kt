package uz.oilanazorati.parentcontrol.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import uz.oilanazorati.parentcontrol.databinding.ActivityMainBinding

/**
 * Ilova birinchi ochilganda foydalanuvchi ikkitadan birini tanlaydi:
 *  - "Bu farzandimning telefoni" -> ChildSetupActivity (kuzatiluvchi qurilma sozlanadi)
 *  - "Men ota-onaman, nazorat qilaman" -> ParentDashboardActivity (statistika ko'riladi)
 *
 * Ikkalasi ham bir xil oila kodi (familyCode) orqali bog'lanadi.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureAnonymousAuth()

        binding.btnChildDevice.setOnClickListener {
            startActivity(Intent(this, ChildSetupActivity::class.java))
        }
        binding.btnParentDevice.setOnClickListener {
            startActivity(Intent(this, ParentDashboardActivity::class.java))
        }
    }

    /** Firestore xavfsizlik qoidalari uchun kamida anonim auth talab qilinadi. */
    private fun ensureAnonymousAuth() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
        }
    }
}
