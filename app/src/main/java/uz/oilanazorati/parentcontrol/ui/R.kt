package uz.oilanazorati.parentcontrol.ui

/**
 * Resource bridge for the Accessibility instruction screenshots.
 * The screenshot files stay in drawable-nodpi; IDs are resolved from the
 * application's generated resources at runtime so ChildSetupActivity can
 * keep the existing screenshot UI unchanged.
 */
object R {
    object drawable {
        val access_step1_installed_apps: Int
            get() = resourceId("access_step1_installed_apps")

        val access_step2_select_app: Int
            get() = resourceId("access_step2_select_app")

        val access_step3_toggle_on: Int
            get() = resourceId("access_step3_toggle_on")

        private fun resourceId(name: String): Int =
            com.google.firebase.FirebaseApp.getInstance()
                .applicationContext
                .resources
                .getIdentifier(name, "drawable", com.google.firebase.FirebaseApp.getInstance().applicationContext.packageName)
    }
}
