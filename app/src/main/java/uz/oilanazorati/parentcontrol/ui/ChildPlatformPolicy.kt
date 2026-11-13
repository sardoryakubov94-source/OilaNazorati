package uz.oilanazorati.parentcontrol.ui

enum class ChildDevicePlatform {
    ANDROID, IOS, UNKNOWN;

    companion object {
        fun from(raw: String?): ChildDevicePlatform = when (raw?.trim()?.lowercase()) {
            "android" -> ANDROID
            "ios", "iphone", "ipados" -> IOS
            else -> UNKNOWN
        }
    }
}

enum class ParentFeature(val wireName: String) {
    LOCATION("location"),
    FOCUS_MODE("focus_mode"),
    CALLS("calls"),
    SMS("sms"),
    CONTACTS("contacts"),
    APP_USAGE("app_usage"),
    SCREEN_TIME_LIMITS("time_limits"),
    SCREEN_TIME_SCHEDULES("schedules"),
    WEBSITE_RESTRICTIONS("website_restrictions"),
    SCREENSHOTS("screenshots"),
    NOTIFICATIONS("notifications"),
    AMBIENT_AUDIO("ambient_audio"),
    RISK_ALERTS("risk_alerts"),
    SIM_INFO("sim_info"),
    TRENDS("trends")
}

/**
 * Android ota-ona panelida faqat bola qurilmasi haqiqatan e'lon qilgan,
 * shu platformada mavjud bo'lgan modullar ko'rsatiladi.
 *
 * Eski Android child build capability ro'yxatini yubormagan bo'lsa,
 * faqat ilovada mavjud Android modullari fallback sifatida beriladi.
 * iOS uchun esa Apple ruxsat bergan imkoniyatlar bilan cheklanadi.
 */
object ChildPlatformPolicy {
    private val androidSupported = setOf(
        ParentFeature.LOCATION,
        ParentFeature.FOCUS_MODE,
        ParentFeature.CALLS,
        ParentFeature.SMS,
        ParentFeature.CONTACTS,
        ParentFeature.APP_USAGE,
        ParentFeature.SCREENSHOTS,
        ParentFeature.NOTIFICATIONS,
        ParentFeature.AMBIENT_AUDIO,
        ParentFeature.RISK_ALERTS,
        ParentFeature.SIM_INFO,
        ParentFeature.TRENDS
    )

    private val iosSupported = setOf(
        ParentFeature.LOCATION,
        ParentFeature.FOCUS_MODE,
        ParentFeature.SCREEN_TIME_LIMITS,
        ParentFeature.SCREEN_TIME_SCHEDULES,
        ParentFeature.WEBSITE_RESTRICTIONS,
        ParentFeature.AMBIENT_AUDIO
    )

    fun capabilities(
        platform: ChildDevicePlatform,
        declaredCapabilities: Set<String>? = null
    ): Set<ParentFeature> {
        val platformSupported = when (platform) {
            ChildDevicePlatform.ANDROID -> androidSupported
            ChildDevicePlatform.IOS -> iosSupported
            ChildDevicePlatform.UNKNOWN -> emptySet()
        }
        if (platformSupported.isEmpty()) return emptySet()

        // Metadata mavjud bo'lsa, faqat e'lon qilingan va platformaga mos
        // capability'lar o'tadi. Eski versiyalarda xavfsiz fallback ishlaydi.
        return if (declaredCapabilities == null) {
            platformSupported
        } else {
            platformSupported.filterTo(mutableSetOf()) { it.wireName in declaredCapabilities }
        }
    }
}
