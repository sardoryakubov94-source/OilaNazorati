package uz.oilanazorati.parentcontrol

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        restoreSavedPairing(this)
        applySavedTheme(this)
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
    }
}
