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

enum class NavTab { HOME, CALLS, SMS, LOCATION, APPS, SCREENSHOT, SETTINGS }

private const val COLOR_ACTIVE = "#2ECC71"

/**
 * Pastki navigatsiya paneli. Sozlamalar endi pastki tab emas — u
 * Bosh sahifadagi yuqoridagi ⚙ tugmasi orqali ochiladi.
 */
fun Activity.bindBottomNav(active: NavTab) {
    val navHome = findViewById<LinearLayout>(R.id.navHome) ?: return
    val navCalls = findViewById<LinearLayout>(R.id.navCalls) ?: return
    val navSms = findViewById<LinearLayout>(R.id.navSms) ?: return
    val navLocation = findViewById<LinearLayout>(R.id.navLocation) ?: return
    val navApps = findViewById<LinearLayout>(R.id.navApps) ?: return
    val navScreenshot = findViewById<LinearLayout>(R.id.navScreenshot) ?: return

    fun highlight(container: LinearLayout, isActive: Boolean) {
        val color = if (isActive) {
            Color.parseColor(COLOR_ACTIVE)
        } else {
            androidx.core.content.ContextCompat.getColor(this, R.color.color_nav_inactive)
        }
        (container.getChildAt(0) as? ImageView)?.setColorFilter(color)
        (container.getChildAt(1) as? TextView)?.setTextColor(color)
    }

    highlight(navHome, active == NavTab.HOME)
    highlight(navCalls, active == NavTab.CALLS)
    highlight(navSms, active == NavTab.SMS)
    highlight(navLocation, active == NavTab.LOCATION)
    highlight(navApps, active == NavTab.APPS)
    highlight(navScreenshot, active == NavTab.SCREENSHOT)

    fun goToTab(targetClass: Class<*>) {
        val intent = Intent(this, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    fun goIfChildSelected(target: () -> Class<*>) {
        if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
            Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            return
        }
        goToTab(target())
    }

    navHome.setOnClickListener {
        if (active != NavTab.HOME) goToTab(ParentDashboardActivity::class.java)
    }
    navCalls.setOnClickListener {
        if (active != NavTab.CALLS) goIfChildSelected { CallHistoryActivity::class.java }
    }
    navSms.setOnClickListener {
        if (active != NavTab.SMS) goIfChildSelected { SmsHistoryActivity::class.java }
    }
    navLocation.setOnClickListener {
        if (active != NavTab.LOCATION) goIfChildSelected { LocationHistoryActivity::class.java }
    }
    navApps.setOnClickListener {
        if (active != NavTab.APPS) goIfChildSelected { TrendsActivity::class.java }
    }
    navScreenshot.setOnClickListener {
        if (active != NavTab.SCREENSHOT) {
            goIfChildSelected { ScreenshotSettingsActivity::class.java }
        }
    }
}
