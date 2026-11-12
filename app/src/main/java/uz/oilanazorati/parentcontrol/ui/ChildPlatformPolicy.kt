package uz.oilanazorati.parentcontrol.ui

enum ChildDevicePlatform {
    ANDROID, IOS, UNKNOWN;

    companion object {
        fun from(raw: String?): ChildDevicePlatform = when (raw?.trim()?.lowercase()) {
            "android" -> ANDROID
            "ios", "iphone", "ipados" -> IOS
            else -> UNKNOWN
        }
    }
}

enum ParentFeature {
    LOCATION,
    FOCUS_MODE,
    CALLS,
    SMS,
    CONTACTS,
    APP_USAGE,
    SCREENSHOTS,
    NOTIFICATIONS,
    AMBIENT_AUDIO,
    RISK_ALERTS,
    SIM_INFO,
    TRENDS
}

/** Android ota-ona ilovasi uchun bola platformasiga mos funksiyalar siyosati. */
object ChildPlatformPolicy {
    fun capabilities(platform: ChildDevicePlatform): Set<ParentFeature> = when (platform) {
        ChildDevicePlatform.ANDROID -> ParentFeature.values().toSet()
        ChildDevicePlatform.IOS -> setOf(
            ParentFeature.LOCATION,
            ParentFeature.FOCUS_MODE
        )
        // Platforma aniqlanmasa Android imkoniyatlarini taxmin qilib ochmaymiz.
        ChildDevicePlatform.UNKNOWN -> emptySet()
    }
}
