package uz.oilanazorati.parentcontrol.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import uz.oilanazorati.parentcontrol.R

/**
 * Ilova bo'ylab barcha bosiladigan tugma va bo'limlarga "bosildi" effekti beradi:
 * bosilganda biroz kichrayadi va xiralashadi, qo'yib yuborilganda qaytadi.
 * Touch hodisasini ISTE'MOL QILMAYDI (false qaytaradi), shuning uchun click
 * va navigatsiya oldingidek, hech qanday kechikishsiz ishlaydi.
 */
object PressFeedback {
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = attach(activity)
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun attach(activity: Activity) {
        val root = activity.window?.decorView ?: return
        scan(root)
        if (root.getTag(R.id.press_feedback_installed) == null) {
            root.setTag(R.id.press_feedback_installed, true)
            root.viewTreeObserver.addOnGlobalLayoutListener { scan(root) }
        }
    }

    private fun scan(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) scan(view.getChildAt(i))
        }
        if (view.getTag(R.id.press_feedback_installed) != null) return
        if (!view.isClickable || !view.hasOnClickListeners()) return
        if (view is EditText || view is SeekBar || view is CompoundButton) return
        view.setTag(R.id.press_feedback_installed, true)
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.78f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
            }
            false
        }
    }
}
