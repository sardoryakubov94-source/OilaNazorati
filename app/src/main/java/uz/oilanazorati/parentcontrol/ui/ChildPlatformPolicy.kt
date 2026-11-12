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
    SCREENSHOTS("screenshots"),
    NOTIFICATIONS("notifications"),
    AMBIENT_AUDIO("ambient_audio"),
    RISK_ALERTS("risk_alerts"),
    SIM_INFO("sim_info"),
    TRENDS("trends")
}

/** Android ota-ona ilovasi uchun bola platformasi va e'lon qilingan imkoniyatlar siyosati. */
object ChildPlatformPolicy {
    fun capabilities(
        platform: ChildDevicePlatform,
        declaredCapabilities: Set<String>? = null
    ): Set<ParentFeature> = when (platform) {
        ChildDevicePlatform.ANDROID -> ParentFeature.values().toSet()
        ChildDevicePlatform.IOS -> {
            // Eski iOS ilovalarida capability ro'yxati yo'q: faqat joylashuvni ko'rsatamiz.
            // Yangi iOS qurilma o'z OS versiyasiga mos ro'yxatni Firestore'ga yozadi.
            val safeDefault = setOf(ParentFeature.LOCATION)
            if (declaredCapabilities == null) safeDefault
            else ParentFeature.values().filter { it in setOf(ParentFeature.LOCATION, ParentFeature.FOCUS_MODE) && it.wireName in declaredCapabilities }.toSet()
        }
        ChildDevicePlatform.UNKNOWN -> emptySet()
    }
}
