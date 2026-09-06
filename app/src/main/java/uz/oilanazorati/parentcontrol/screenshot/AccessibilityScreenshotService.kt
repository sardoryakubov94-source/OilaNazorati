package uz.oilanazorati.parentcontrol.screenshot

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Optional, user-enabled accessibility screenshot test path.
 * It is deliberately separate from the existing MediaProjection service.
 */
class AccessibilityScreenshotService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TEST_SCREENSHOT) captureForTest { bitmap ->
                Toast.makeText(this@AccessibilityScreenshotService,
                    if (bitmap != null) "✅ Accessibility screenshot: muvaffaqiyatli" else "❌ Accessibility screenshot: xato",
                    Toast.LENGTH_SHORT).show()
                bitmap?.recycle()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_TEST_SCREENSHOT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(testReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(testReceiver, filter)
        }
    }

    fun captureForTest(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainHandler.executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                buffer.close()
                onResult(bitmap)
            }
            override fun onFailure(errorCode: Int) = onResult(null)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(testReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_TEST_SCREENSHOT = "uz.oilanazorati.action.ACCESSIBILITY_SCREENSHOT_TEST"
    }
}
