package uz.oilanazorati.parentcontrol.util

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.Calendar

/**
 * Bolaning BUGUNGI eng ko'p ishlatgan ilovalarini (ekranda haqiqiy
 * o'tkazgan vaqtiga qarab, UsageStatsManager orqali) hisoblaydigan
 * YAGONA markazlashtirilgan mexanizm.
 *
 * MUHIM: bu — bildirishnoma SONIGA emas, balki ilova QANCHA VAQT old
 * planda turganiga asoslanadi. Masalan Snapchat kabi ilova tez-tez
 * bildirishnoma yuborsa-yu, bola uni kamdan-kam ochsa — u "ko'p
 * ishlatiladigan" hisoblanmaydi. Aksincha, Instagram yoki SMS kabi
 * bola haqiqatan ko'p vaqt o'tkazadigan ilovalar shu ro'yxatga kiradi.
 *
 * Avtomatik skrinshot (Top-3 ilova) va premium bildirishnoma filtri
 * ikkalasi ham AYNAN shu funksiyaga tayanadi — shunda "eng ko'p
 * ishlatilgan ilova" ta'rifi butun dastur bo'ylab bir xil bo'lib qoladi.
 */
object TopUsedAppsHelper {

    fun computeTopApps(context: Context, limit: Int = 3): Set<String> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptySet()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(startOfDay, now)
        val pm = context.packageManager

        return stats.filter { (pkg, usage) ->
            pkg != context.packageName &&
                usage.totalTimeInForeground > 0 &&
                (safeApplicationInfo(pm, pkg)?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) == 0
        }.entries
            .sortedByDescending { it.value.totalTimeInForeground }
            .take(limit)
            .map { it.key }
            .toSet()
    }

    private fun safeApplicationInfo(pm: android.content.pm.PackageManager, pkg: String) =
        try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
}
