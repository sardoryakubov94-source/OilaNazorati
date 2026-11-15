package uz.oilanazorati.parentcontrol.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import uz.oilanazorati.parentcontrol.ui.MainActivity

/**
 * Launcher ko'rinishini oldindan manifestda e'lon qilingan, neytral
 * Bulut servis ikonkalari orasida almashtiradi. Ilova nomi o'zgarmaydi.
 */
object ChildAppAppearanceManager {
    const val DEFAULT = "default"
    const val CLOUD = "cloud"
    const val CALCULATOR = "calculator"
    const val NOTEBOOK = "notebook"
    const val GEOMETRIC = "geometric"

    private val aliases = linkedMapOf(
        DEFAULT to "uz.oilanazorati.parentcontrol.ui.LauncherDefaultAlias",
        CLOUD to "uz.oilanazorati.parentcontrol.ui.LauncherCloudAlias",
        CALCULATOR to "uz.oilanazorati.parentcontrol.ui.LauncherCalculatorAlias",
        NOTEBOOK to "uz.oilanazorati.parentcontrol.ui.LauncherNotebookAlias",
        GEOMETRIC to "uz.oilanazorati.parentcontrol.ui.LauncherGeometricAlias"
    )

    fun apply(context: Context, appearance: String?) {
        val selected = appearance?.takeIf { aliases.containsKey(it) } ?: DEFAULT
        val pm = context.packageManager
        aliases.forEach { (key, className) ->
            val component = ComponentName(context.packageName, className)
            val state = if (key == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            try {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            } catch (_: Exception) {
                // Noma'lum yoki yangilanmagan launcher alias bo'lsa ilova ishlashda davom etadi.
            }
        }
    }
}
