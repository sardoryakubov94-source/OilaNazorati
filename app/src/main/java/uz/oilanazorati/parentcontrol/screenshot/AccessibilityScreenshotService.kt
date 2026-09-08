package uz.oilanazorati.parentcontrol.screenshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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

/** User-enabled screenshot transport. Auto capture only runs while a selected target app is foreground. */
class AccessibilityScreenshotService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = Executor { command -> mainHandler.post(command) }
    private var settings: ScreenshotSettings = ScreenshotSettings()
    private var settingsListener: ListenerRegistration? = null
    private var requestListener: ListenerRegistration? = null
    private var captureRunning: Boolean = false
    private var autoWatchScheduled: Boolean = false
    private var burstPackage: String? = null
    private var burstThreshold: Int = 0
    private var burstCount: Int = 0
    private var burstScheduledRunnable: Runnable? = null

    // "Avtomatik TOP 3" va "qo'lda tanlangan ilovalar" — ikkita mustaqil
    // manba. TOP-3 o'chirilgan bo'lsa ham, qo'lda tanlangan ilovalar kuzatiladi.
    private val autoCaptureActive: Boolean
        get() = settings.autoTop3Enabled || settings.manualPackageNames.isNotEmpty()

    private val autoWatchRunnable: Runnable = object : Runnable {
        override fun run() {
            autoWatchScheduled = false
            evaluateAndQueue()
            if (settings.enabled && autoCaptureActive) scheduleAutoWatch()
        }
    }

    private val testReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TEST_SCREENSHOT) captureForTest { bitmap: Bitmap? ->
                Toast.makeText(this@AccessibilityScreenshotService, if (bitmap != null) "Accessibility screenshot: muvaffaqiyatli" else "Accessibility screenshot: xato", Toast.LENGTH_SHORT).show()
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
        settingsListener = ScreenshotRepository.listenSettings { newSettings: ScreenshotSettings ->
            settings = newSettings
            if (!settings.enabled || !autoCaptureActive) clearBurst()
            if (settings.enabled && autoCaptureActive) scheduleAutoWatch()
        }
        requestListener = ScreenshotRepository.listenScreenshotRequests { requestId: String ->
            if (settings.enabled) queueRemoteCapture(requestId)
        }
    }

    private fun scheduleAutoWatch(): Unit {
        if (autoWatchScheduled) return
        autoWatchScheduled = true
        mainHandler.postDelayed(autoWatchRunnable, AUTO_WATCH_INTERVAL_MS)
    }

    private fun queueRemoteCapture(requestId: String): Unit {
        if (!settings.enabled || captureRunning || !isScreenInteractive()) return
        ScreenshotRepository.markScreenshotRequest(requestId, "processing")
        captureAndUpload(currentForegroundPackage() ?: "uz.oilanazorati.screen", 0, currentUsageSeconds(), "remote_$requestId", requestId, null)
    }

    /**
     * Auto trigger is NOT a wall-clock screenshot timer.
     * It checks the currently foreground app and its continuous foreground time.
     * Once that same target app stays active for the configured threshold
     * (15/30/45/60 min), exactly 3 screenshots are taken, one minute apart.
     */
    private fun evaluateAndQueue(): Unit {
        if (!settings.enabled || !autoCaptureActive || captureRunning || burstPackage != null) return
        if (!isScreenInteractive()) {
            clearBurst()
            return
        }

        val usm: UsageStatsManager = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now: Long = System.currentTimeMillis()
        val foreground: Pair<String, Long> = currentForegroundInfo(usm, now) ?: return
        val current: String = foreground.first
        val continuousSec: Long = ((now - foreground.second).coerceAtLeast(0L)) / 1000L
        val frequency: Long = settings.frequencyMinutes.coerceIn(15, 60).toLong()

        // Threshold is based ONLY on continuous time in the active app.
        // Daily accumulated usage is deliberately not used as the trigger.
        if (continuousSec < frequency * 60L) return

        val auto: Set<String> = if (settings.autoTop3Enabled) {
            val start: Long = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            usm.queryAndAggregateUsageStats(start, now)
                .filter { (pkg, stat) ->
                    pkg != packageName && stat.totalTimeInForeground > 0 &&
                        (getApplicationInfoSafe(pkg)?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) ?: 0) == 0
                }
                .entries.sortedByDescending { it.value.totalTimeInForeground }
                .take(3).map { it.key }.toSet()
        } else emptySet()

        val targets = auto + settings.manualPackageNames.toSet()
        if (current !in targets) return

        // Trigger at the first configured threshold crossing only; after the
        // 3-shot burst, the next threshold is reached only after another full
        // frequency interval of continuous use.
        val threshold = (continuousSec / 60L / frequency * frequency).toInt()
        val child: String = FirebaseRepo.childId ?: return
        val key: String = triggerKey(child, current, todayKey(), threshold)
        ScreenshotRepository.reserveTrigger(key) { reserved: Boolean ->
            if (!reserved || !settings.enabled || !autoCaptureActive || !isScreenInteractive() || currentForegroundPackage() != current) return@reserveTrigger
            burstPackage = current
            burstThreshold = threshold
            burstCount = 0
            scheduleBurstCapture(0L)
        }
    }

    private fun scheduleBurstCapture(delayMs: Long): Unit {
        burstScheduledRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            burstScheduledRunnable = null
            runBurstCapture()
        }
        burstScheduledRunnable = runnable
        if (delayMs <= 0L) mainHandler.post(runnable) else mainHandler.postDelayed(runnable, delayMs)
    }

    private fun runBurstCapture(): Unit {
        val target: String = burstPackage ?: return
        if (!settings.enabled || !autoCaptureActive || captureRunning || burstCount >= AUTO_BURST_COUNT) {
            if (burstCount >= AUTO_BURST_COUNT) clearBurst()
            return
        }
        if (!isScreenInteractive()) {
            clearBurst()
            return
        }
        val current: String = currentForegroundPackage() ?: run { clearBurst(); return }
        if (current != target) {
            clearBurst()
            return
        }
        val usage: Long = currentUsageSeconds()
        burstCount++
        captureAndUpload(target, burstThreshold, usage, "auto_burst_${todayKey()}_${target.hashCode()}_${burstThreshold}_$burstCount", null) {
            if (burstCount < AUTO_BURST_COUNT && burstPackage == target && isScreenInteractive() && currentForegroundPackage() == target) {
                scheduleBurstCapture(AUTO_BURST_INTERVAL_MS)
            } else {
                clearBurst()
            }
        }
    }

    private fun captureAndUpload(packageName: String, threshold: Int, usageSeconds: Long, key: String, remoteRequestId: String?, onFinished: (() -> Unit)?): Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishCapture(false, key, remoteRequestId, "Bu Android versiyasida Accessibility screenshot mavjud emas", onFinished)
            return
        }
        if (captureRunning || !isScreenInteractive()) return
        captureRunning = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hardware: Bitmap? = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    screenshot.hardwareBuffer.close()
                    if (hardware == null) { finishCapture(false, key, remoteRequestId, "Screenshot bitmap tayyorlanmadi", onFinished); return }
                    val bitmap: Bitmap? = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    hardware.recycle()
                    if (bitmap == null) { finishCapture(false, key, remoteRequestId, "Screenshot bitmap nusxalanmadi", onFinished); return }
                    val file = File(cacheDir, "accessibility_screenshot_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    bitmap.recycle()
                    val now: Long = System.currentTimeMillis()
                    val meta = ScreenshotMetadata(id = "${now}_${threshold}_${packageName.hashCode()}", childId = FirebaseRepo.childId.orEmpty(), familyId = FirebaseRepo.familyCode.orEmpty(), packageName = packageName, appLabel = label(packageName), capturedAt = now, date = todayKey(), dailyUsageSeconds = usageSeconds, thresholdMinute = threshold)
                    ScreenshotRepository.upload(file, meta) { ok: Boolean ->
                        file.delete()
                        finishCapture(ok, key, remoteRequestId, if (ok) "Screenshot tayyor" else "Screenshot yuklanmadi", onFinished)
                    }
                } catch (t: Throwable) {
                    finishCapture(false, key, remoteRequestId, t.message ?: "Accessibility screenshot xatosi", onFinished)
                }
            }
            override fun onFailure(errorCode: Int): Unit = finishCapture(false, key, remoteRequestId, "Accessibility screenshot xatosi: $errorCode", onFinished)
        })
    }

    private fun finishCapture(ok: Boolean, key: String, remoteRequestId: String?, message: String, onFinished: (() -> Unit)?): Unit {
        if (remoteRequestId != null) ScreenshotRepository.markScreenshotRequest(remoteRequestId, if (ok) "completed" else "failed", message)
        captureRunning = false
        onFinished?.invoke()
    }

    private fun clearBurst(): Unit {
        burstScheduledRunnable?.let { mainHandler.removeCallbacks(it) }
        burstScheduledRunnable = null
        burstPackage = null
        burstThreshold = 0
        burstCount = 0
    }

    fun captureForTest(onResult: (Bitmap?) -> Unit): Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return onResult(null)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap: Bitmap? = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                screenshot.hardwareBuffer.close(); onResult(bitmap)
            }
            override fun onFailure(errorCode: Int): Unit = onResult(null)
        })
    }

    /** Returns the current app only when its latest foreground event has not been followed by a background event. */
    private fun currentForegroundInfo(usm: UsageStatsManager, now: Long): Pair<String, Long>? {
        val events = usm.queryEvents((now - 24 * 60 * 60_000L).coerceAtLeast(0L), now)
        val event = android.app.usage.UsageEvents.Event()
        val starts = HashMap<String, Long>()
        var currentPkg: String? = null
        var currentStart = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    starts[event.packageName] = event.timeStamp
                    currentPkg = event.packageName
                    currentStart = event.timeStamp
                }
                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = starts.remove(event.packageName)
                    if (currentPkg == event.packageName && start != null && event.timeStamp >= currentStart) {
                        currentPkg = null
                        currentStart = 0L
                    }
                }
            }
        }
        return if (currentPkg != null && currentStart > 0L) currentPkg!! to currentStart else null
    }

    private fun currentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        return currentForegroundInfo(usm, now)?.first
    }

    private fun currentUsageSeconds(): Long {
        val pkg: String = currentForegroundPackage() ?: return 0L
        val usm: UsageStatsManager = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L
        val start: Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())[pkg]?.totalTimeInForeground ?: 0L) / 1000L
    }

    private fun isScreenInteractive(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return power.isInteractive && keyguard?.isKeyguardLocked != true
    }

    private fun getApplicationInfoSafe(pkg: String) = try { packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
    private fun label(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun triggerKey(child: String, pkg: String, date: String, threshold: Int): String = "${date}_${child.hashCode()}_${pkg.hashCode()}_$threshold"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (settings.enabled && autoCaptureActive) scheduleAutoWatch()
    }

    override fun onInterrupt(): Unit = Unit
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        settingsListener?.remove(); requestListener?.remove()
        clearBurst()
        ScreenshotRepository.updateAccessibilityStatus(false)
        runCatching { unregisterReceiver(testReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_TEST_SCREENSHOT = "uz.oilanazorati.action.ACCESSIBILITY_SCREENSHOT_TEST"
        const val AUTO_WATCH_INTERVAL_MS = 30_000L
        const val AUTO_BURST_INTERVAL_MS = 60_000L
        const val AUTO_BURST_COUNT = 3

        fun isServiceEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo?.let {
                    it.packageName == context.packageName && it.name == AccessibilityScreenshotService::class.java.name
                } == true
            }
        }
    }
}
