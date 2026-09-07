package uz.oilanazorati.parentcontrol.screenshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStatsManager
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
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.model.ScreenshotMetadata
import uz.oilanazorati.parentcontrol.model.ScreenshotSettings
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/** User-enabled one-shot screenshot transport using Android AccessibilityService. */
class AccessibilityScreenshotService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private var settings = ScreenshotSettings()
    private var settingsListener: ListenerRegistration? = null
    private var requestListener: ListenerRegistration? = null
    private var captureRunning = false

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TEST_SCREENSHOT) captureForTest { bitmap ->
                Toast.makeText(this@AccessibilityScreenshotService,
                    if (bitmap != null) "Accessibility screenshot: muvaffaqiyatli" else "Accessibility screenshot: xato",
                    Toast.LENGTH_SHORT).show()
                bitmap?.recycle()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_TEST_SCREENSHOT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(testReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(testReceiver, filter)
        ScreenshotRepository.updateAccessibilityStatus(true)
        settingsListener = ScreenshotRepository.listenSettings { newSettings -> settings = newSettings }
        requestListener = ScreenshotRepository.listenScreenshotRequests { requestId ->
            if (settings.enabled) queueRemoteCapture(requestId)
        }
    }

    private fun queueRemoteCapture(requestId: String) {
        if (!settings.enabled || captureRunning) return
        ScreenshotRepository.markScreenshotRequest(requestId, "processing")
        captureAndUpload(currentForegroundPackage() ?: "uz.oilanazorati.screen", 0, currentUsageSeconds(), "remote_$requestId", requestId)
    }

    private fun evaluateAndQueue() {
        if (!settings.enabled || !settings.autoTop3Enabled || captureRunning) return
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val stats = usm.queryAndAggregateUsageStats(start, now)
        val userStats = stats.filter { (pkg, stat) ->
            pkg != packageName && stat.totalTimeInForeground > 0 &&
                (getApplicationInfoSafe(pkg)?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) ?: 0) == 0
        }
        val auto = userStats.entries.sortedByDescending { it.value.totalTimeInForeground }.take(3).map { it.key }.toSet()
        val targets = auto + settings.manualPackageNames.toSet()
        val current = currentForegroundPackage() ?: return
        if (current !in targets) return
        val usageSec = (stats[current]?.totalTimeInForeground ?: 0L) / 1000L
        val frequency = settings.frequencyMinutes.coerceIn(15, 60).toLong()
        val minute = usageSec / 60L
        val base = (minute / frequency) * frequency
        if (base < frequency) return
        val child = FirebaseRepo.childId ?: return
        val key = triggerKey(child, current, todayKey(), base.toInt())
        ScreenshotRepository.reserveTrigger(key) { reserved ->
            if (reserved && settings.enabled && !captureRunning) captureAndUpload(current, base.toInt(), usageSec, key, null)
        }
    }

    private fun captureAndUpload(packageName: String, threshold: Int, usageSeconds: Long, key: String, remoteRequestId: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishCapture(false, key, remoteRequestId, "Bu Android versiyasida Accessibility screenshot mavjud emas")
            return
        }
        if (captureRunning) return
        captureRunning = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    screenshot.hardwareBuffer.close()
                    if (hardware == null) { finishCapture(false, key, remoteRequestId, "Screenshot bitmap tayyorlanmadi"); return }
                    val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    hardware.recycle()
                    if (bitmap == null) { finishCapture(false, key, remoteRequestId, "Screenshot bitmap nusxalanmadi"); return }
                    val file = File(cacheDir, "accessibility_screenshot_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    bitmap.recycle()
                    val now = System.currentTimeMillis()
                    val meta = ScreenshotMetadata(
                        id = "${now}_${threshold}_${packageName.hashCode()}", childId = FirebaseRepo.childId.orEmpty(), familyId = FirebaseRepo.familyCode.orEmpty(),
                        packageName = packageName, appLabel = label(packageName), capturedAt = now, date = todayKey(), dailyUsageSeconds = usageSeconds, thresholdMinute = threshold
                    )
                    ScreenshotRepository.upload(file, meta) { ok ->
                        file.delete()
                        finishCapture(ok, key, remoteRequestId, if (ok) "Screenshot tayyor" else "Screenshot yuklanmadi")
                    }
                } catch (t: Throwable) {
                    finishCapture(false, key, remoteRequestId, t.message ?: "Accessibility screenshot xatosi")
                }
            }
            override fun onFailure(errorCode: Int) = finishCapture(false, key, remoteRequestId, "Accessibility screenshot xatosi: $errorCode")
        })
    }

    private fun finishCapture(ok: Boolean, key: String, remoteRequestId: String?, message: String) {
        if (remoteRequestId != null) ScreenshotRepository.markScreenshotRequest(remoteRequestId, if (ok) "completed" else "failed", message)
        else getSharedPreferences("screenshot_trigger_state", MODE_PRIVATE).edit().putBoolean(key, ok).apply()
        captureRunning = false
    }

    fun captureForTest(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return onResult(null)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                screenshot.hardwareBuffer.close(); onResult(bitmap)
            }
            override fun onFailure(errorCode: Int) = onResult(null)
        })
    }

    private fun currentForegroundPackage(): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis(); val events = usm.queryEvents((now - 10 * 60_000L).coerceAtLeast(0L), now)
        val event = android.app.usage.UsageEvents.Event(); var pkg: String? = null; var timestamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp >= timestamp) {
                timestamp = event.timeStamp; pkg = event.packageName
            }
        }
        return pkg
    }

    private fun currentUsageSeconds(): Long {
        val pkg = currentForegroundPackage() ?: return 0L
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())[pkg]?.totalTimeInForeground ?: 0L) / 1000L
    }

    private fun getApplicationInfoSafe(pkg: String) = try { packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
    private fun label(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun todayKey() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun triggerKey(child: String, pkg: String, date: String, threshold: Int) = "${date}_${child.hashCode()}_${pkg.hashCode()}_$threshold"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        settingsListener?.remove(); requestListener?.remove()
        ScreenshotRepository.updateAccessibilityStatus(false)
        runCatching { unregisterReceiver(testReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_TEST_SCREENSHOT = "uz.oilanazorati.action.ACCESSIBILITY_SCREENSHOT_TEST"
        fun isServiceEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo?.packageName == context.packageName && serviceInfo.name == AccessibilityScreenshotService::class.java.name
            }
        }
    }
}
