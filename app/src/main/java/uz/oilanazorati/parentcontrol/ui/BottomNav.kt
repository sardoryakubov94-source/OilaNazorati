package uz.oilanazorati.parentcontrol.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

enum class NavTab { HOME, CALLS, LOCATION, APPS, SETTINGS }

private const val COLOR_ACTIVE = "#2ECC71"
private const val COLOR_INACTIVE = "#8B96A5"

/**
 * res/layout/layout_bottom_nav.xml'ni <include> qilgan har bir asosiy
 * ekran (Bosh sahifa, Aloqa, Joylashuv, Ilovalar, Sozlama) shu funksiyani
 * setContentView/ViewBinding'dan KEYIN chaqiradi — shu sababli panel
 * hamma ekranda bir xil ko'rinadi va yo'qolib qolmaydi:
 *
 *   bindBottomNav(NavTab.CALLS)
 *
 * Joriy tab (icon + yozuv) yashil rangda belgilanadi; boshqa tabga
 * bosilsa mos ekranga o'tiladi va joriy ekran finish() qilinadi (tekis/
 * tab-uslubidagi navigatsiya — ekranlar cheksiz qatlamlanib qolmaydi).
 * "Sozlama" endi to'liq alohida SettingsActivity ekraniga olib boradi.
 * Oila kodi yoki farzand hali tanlanmagan bo'lsa, Aloqa/Joylashuv/
 * Ilovalar tablariga o'tish o'rniga ogohlantirish ko'rsatiladi.
 */
fun Activity.bindBottomNav(active: NavTab) {
    val navHome = findViewById<LinearLayout>(R.id.navHome) ?: return
    val navCalls = findViewById<LinearLayout>(R.id.navCalls) ?: return
    val navLocation = findViewById<LinearLayout>(R.id.navLocation) ?: return
    val navApps = findViewById<LinearLayout>(R.id.navApps) ?: return
    val navSettings = findViewById<LinearLayout>(R.id.navSettings) ?: return

    fun highlight(container: LinearLayout, isActive: Boolean) {
        val color = Color.parseColor(if (isActive) COLOR_ACTIVE else COLOR_INACTIVE)
        (container.getChildAt(0) as? ImageView)?.setColorFilter(color)
        (container.getChildAt(1) as? TextView)?.setTextColor(color)
    }
    highlight(navHome, active == NavTab.HOME)
    highlight(navCalls, active == NavTab.CALLS)
    highlight(navLocation, active == NavTab.LOCATION)
    highlight(navApps, active == NavTab.APPS)
    highlight(navSettings, active == NavTab.SETTINGS)

    fun goIfChildSelected(target: () -> Class<*>) {
        if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
            Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, target()))
        finish()
    }

    navHome.setOnClickListener {
        if (active != NavTab.HOME) {
            startActivity(Intent(this, ParentDashboardActivity::class.java))
            finish()
        }
    }
    navCalls.setOnClickListener { if (active != NavTab.CALLS) goIfChildSelected { CallHistoryActivity::class.java } }
    navLocation.setOnClickListener { if (active != NavTab.LOCATION) goIfChildSelected { LocationHistoryActivity::class.java } }
    navApps.setOnClickListener { if (active != NavTab.APPS) goIfChildSelected { TrendsActivity::class.java } }
    navSettings.setOnClickListener {
        if (active != NavTab.SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
    }
}
