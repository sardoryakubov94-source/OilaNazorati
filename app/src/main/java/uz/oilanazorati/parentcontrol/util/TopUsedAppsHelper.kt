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
        // Batareya: "Top-3" juda sekin o'zgaradi, aggregatsiya esa butun kunlik hodisalarni o'qiydi.
        // Shu sabab natija 5 daqiqa keshlanadi (avto-skrinshot tekshiruvi har daqiqa so'raydi).
        cached?.let { c ->
            if (c.limit == limit && c.startOfDay == startOfDay && now - c.at < CACHE_TTL_MS) return c.value
        }
        val stats = usm.queryAndAggregateUsageStats(startOfDay, now)
        val pm = context.packageManager

        val result = stats.filter { (pkg, usage) ->
            pkg != context.packageName &&
                usage.totalTimeInForeground > 0 &&
                (safeApplicationInfo(pm, pkg)?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) == 0
        }.entries
            .sortedByDescending { it.value.totalTimeInForeground }
            .take(limit)
            .map { it.key }
            .toSet()
        cached = Cached(limit, startOfDay, now, result)
        return result
    }

    private const val CACHE_TTL_MS = 5 * 60_000L
    private class Cached(val limit: Int, val startOfDay: Long, val at: Long, val value: Set<String>)
    @Volatile private var cached: Cached? = null

    private fun safeApplicationInfo(pm: android.content.pm.PackageManager, pkg: String) =
        try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
}
