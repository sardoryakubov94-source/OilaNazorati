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
import uz.oilanazorati.parentcontrol.model.RiskEvent
import uz.oilanazorati.parentcontrol.risk.RiskAnalysisEngine
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import uz.oilanazorati.parentcontrol.risk.MediaRiskAnalyzer

/** User-enabled screenshot transport. Auto capture only runs while a selected target app is foreground. */
class AccessibilityScreenshotService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = Executor { command -> mainHandler.post(command) }
    // Screenshot siqish/yozish HAMDA yangi rasm-tahlil (MediaRiskAnalyzer)
    // og'ir amallar — bularni asosiy (UI) oqimda emas, shu fon oqimida
    // bajaramiz, aks holda ilova/ekran vaqtincha "qotib qolishi" mumkin.
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var settings: ScreenshotSettings = ScreenshotSettings()
    private var settingsListener: ListenerRegistration? = null
    private var requestListener: ListenerRegistration? = null
    private var captureRunning: Boolean = false
    private var autoWatchScheduled: Boolean = false
    private var burstPackage: String? = null
    private var burstThreshold: Int = 0
    private var burstCount: Int = 0
    private var burstScheduledRunnable: Runnable? = null
    private val recentRiskEvents = LinkedHashMap<String, Long>()

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
        if (!settings.enabled) return
        if (captureRunning) return
        if (!isPowerOn()) {
            // Ekran butunlay o'chiq — qisqa muddatga uyg'otib ko'ramiz, chunki
            // qulflangan (lekin yoniq) ekrandan farqli o'laroq, o'chiq
            // ekrandan hech qanday screenshot API orqali suratga olib
            // bo'lmaydi (bu OS darajasidagi cheklov).
            wakeScreenBriefly()
            mainHandler.postDelayed({
                if (!isPowerOn()) {
                    ScreenshotRepository.markScreenshotRequest(requestId, "failed", LOCKED_SCREEN_MESSAGE)
                } else {
                    ScreenshotRepository.markScreenshotRequest(requestId, "processing")
                    captureAndUpload(currentForegroundPackage() ?: "uz.oilanazorati.screen", 0, currentUsageSeconds(), "remote_$requestId", requestId, requireUnlocked = false, onFinished = null)
                }
            }, 700L)
            return
        }
        ScreenshotRepository.markScreenshotRequest(requestId, "processing")
        // Qo'lda so'ralgan screenshot uchun faqat ekran YONIQ bo'lishi kifoya —
        // qulflangan bo'lsa ham (masalan qulf ekrani ko'rinishi) suratga olinadi.
        captureAndUpload(currentForegroundPackage() ?: "uz.oilanazorati.screen", 0, currentUsageSeconds(), "remote_$requestId", requestId, requireUnlocked = false, onFinished = null)
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

        if (continuousSec < frequency * 60L) return

        val auto: Set<String> = if (settings.autoTop3Enabled) {
            uz.oilanazorati.parentcontrol.util.TopUsedAppsHelper.computeTopApps(this, 3)
        } else emptySet()

        val targets = auto + settings.manualPackageNames.toSet()
        if (current !in targets) return

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
        captureAndUpload(target, burstThreshold, usage, "auto_burst_${todayKey()}_${target.hashCode()}_${burstThreshold}_$burstCount", null, requireUnlocked = true) {
            if (burstCount < AUTO_BURST_COUNT && burstPackage == target && isScreenInteractive() && currentForegroundPackage() == target) {
                scheduleBurstCapture(AUTO_BURST_INTERVAL_MS)
            } else {
                clearBurst()
            }
        }
    }

    private fun captureAndUpload(packageName: String, threshold: Int, usageSeconds: Long, key: String, remoteRequestId: String?, requireUnlocked: Boolean = true, riskCategory: String = "", sensitiveEvidence: Boolean = false, onFinished: (() -> Unit)?): Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishCapture(false, key, remoteRequestId, "Bu Android versiyasida Accessibility screenshot mavjud emas", onFinished)
            return
        }
        // Avtomatik (top-3/tanlangan ilova) kuzatuv uchun ekran ochiq VA
        // qulfsiz bo'lishi shart — aks holda foreground ilova umuman
        // ko'rinmaydi. Qo'lda so'ralgan screenshot uchun esa faqat ekran
        // yoniq bo'lishi kifoya (qulf ekrani ham suratga olinadi).
        val screenOk = if (requireUnlocked) isScreenInteractive() else isPowerOn()
        if (captureRunning || !screenOk) {
            if (remoteRequestId != null && !screenOk) finishCapture(false, key, remoteRequestId, LOCKED_SCREEN_MESSAGE, onFinished)
            return
        }
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
                    // Rasm siqish/yozish HAMDA MediaRiskAnalyzer tahlili og'ir amallar —
                    // shu sabab qolgan bosqichni fon oqimida davom ettiramiz, UI
                    // (asosiy) oqim band qolmasin.
                    bgExecutor.execute {
                        processCapturedBitmap(bitmap, packageName, threshold, usageSeconds, key, remoteRequestId, riskCategory, sensitiveEvidence, onFinished)
                    }
                } catch (t: Throwable) {
                    finishCapture(false, key, remoteRequestId, t.message ?: "Accessibility screenshot xatosi", onFinished)
                }
            }
            override fun onFailure(errorCode: Int): Unit = finishCapture(false, key, remoteRequestId, "Accessibility screenshot xatosi: $errorCode", onFinished)
        })
    }

    /**
     * Screenshot bitmapini FON OQIMIDA qayta ishlaydi: avval rasmning o'zini
     * MediaRiskAnalyzer bilan tekshiradi (18+ tasvir bormi), so'ng — agar
     * xavf topilsa yoki matn tahlili allaqachon sezgir deb belgilagan
     * bo'lsa — dalilni xiralashtirib (blur) saqlaydi va RiskEvent yozadi.
     * Rasmning o'zi hech qachon serverga yuborilmaydi — faqat xulosa.
     */
    private fun processCapturedBitmap(
        bitmap: Bitmap, packageName: String, threshold: Int, usageSeconds: Long,
        key: String, remoteRequestId: String?, riskCategory: String, sensitiveEvidence: Boolean, onFinished: (() -> Unit)?
    ) {
        try {
            val mediaVerdict = runCatching { MediaRiskAnalyzer.analyze(applicationContext, bitmap) }.getOrNull()
            val finalRiskCategory = mediaVerdict?.category ?: riskCategory
            // MUHIM: faqat "tasdiqlangan" (mediaVerdict.sensitive == true) holatda
            // dalil xiralashtiriladi. Shubhali-lekin-noaniq holatda (sensitive=false)
            // dalil OCHIQ qoladi — xuddi matn-asosidagi tahlildagi bir xil qoidaga
            // muvofiq: ota-ona faqat aniq/tasdiqlangan holatlarda rasmni ko'rmaydi,
            // noaniq holatlarda esa o'zi ko'rib baholay oladi.
            val finalSensitive = sensitiveEvidence || (mediaVerdict?.sensitive == true)

            if (mediaVerdict != null) {
                val now = System.currentTimeMillis()
                val appName = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
                }.getOrDefault(packageName)
                FirebaseRepo.logRiskEvent(
                    RiskEvent(
                        id = "risk_media_${now}_${packageName.hashCode()}", category = mediaVerdict.category,
                        severity = mediaVerdict.severity, confidence = mediaVerdict.confidence,
                        packageName = packageName, appName = appName, source = "image_classifier",
                        summary = mediaVerdict.summary, contextText = "", mediaType = "IMAGE",
                        mediaState = if (mediaVerdict.sensitive) "HIDDEN_SENSITIVE" else "VISIBLE",
                        capturedAt = now, evidenceAvailable = true, sensitive = mediaVerdict.sensitive
                    )
                )
            }

            val file = File(cacheDir, "accessibility_screenshot_${System.currentTimeMillis()}.jpg")
            val outputBitmap = if (finalSensitive) blurForEvidence(bitmap) else bitmap
            FileOutputStream(file).use { outputBitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (outputBitmap !== bitmap) outputBitmap.recycle()
            bitmap.recycle()
            val now: Long = System.currentTimeMillis()
            val meta = ScreenshotMetadata(id = "${now}_${threshold}_${packageName.hashCode()}", childId = FirebaseRepo.childId.orEmpty(), familyId = FirebaseRepo.familyCode.orEmpty(), packageName = packageName, appLabel = label(packageName), capturedAt = now, date = todayKey(), dailyUsageSeconds = usageSeconds, thresholdMinute = threshold, riskCategory = finalRiskCategory, sensitiveEvidence = finalSensitive)
            ScreenshotRepository.upload(file, meta) { ok: Boolean ->
                file.delete()
                finishCapture(ok, key, remoteRequestId, if (ok) "Screenshot tayyor" else "Screenshot yuklanmadi", onFinished)
            }
        } catch (t: Throwable) {
            finishCapture(false, key, remoteRequestId, t.message ?: "Accessibility screenshot xatosi", onFinished)
        }
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

    /** Faqat displey quvvatlanganini (yoniq) tekshiradi — qulf holatidan qat'i nazar. */
    private fun isPowerOn(): Boolean = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

    /**
     * Ekran butunlay o'chiq bo'lganda, qo'lda so'ralgan screenshot uchun uni
     * qisqa muddatga (bir necha soniya) uyg'otadi. Qulflangan holatda ham
     * qulf ekrani ko'rinadi va shu holat suratga olinadi — bu "chiroq
     * o'chgan"dan farqli, chunki hech qanday screenshot API ekran o'chiq
     * paytda hech narsani suratga ololmaydi (bu OS darajasidagi cheklov,
     * faqat shu ilovaga xos emas).
     */
    private fun wakeScreenBriefly() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            @Suppress("DEPRECATION")
            val wakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "OilaNazorati:ScreenshotWake"
            )
            wakeLock.acquire(3000L)
            mainHandler.postDelayed({ try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Throwable) {} }, 2500L)
        } catch (_: Throwable) {}
    }

    private fun blurForEvidence(source: Bitmap): Bitmap {
        val smallW = (source.width / 24).coerceAtLeast(12)
        val smallH = (source.height / 24).coerceAtLeast(12)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        return Bitmap.createScaledBitmap(small, source.width, source.height, false).also { small.recycle() }
    }

    private fun getApplicationInfoSafe(pkg: String) = try { packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
    private fun label(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun triggerKey(child: String, pkg: String, date: String, threshold: Int): String = "${date}_${child.hashCode()}_${pkg.hashCode()}_$threshold"

    // ---------------- Video kadrini davriy tekshirish ----------------
    // MUHIM — batareya va Firebase kvotasini tejash qoidalari:
    // 1) Faqat VIDEO KO'RINISHI aniqlangan ilovada ishga tushadi (har doim
    //    emas), 2) kamida 15 soniyalik oraliq bilan (tez-tez emas),
    // 3) uzluksiz eng ko'p MAX_VIDEO_SAMPLES marta (keyin to'xtaydi,
    //    qayta aniqlanishi kerak), 4) ENG MUHIMI — xavf TOPILMASA hech
    //    qanday fayl yozilmaydi, Firestore'ga yozilmaydi, Storage'ga
    //    yuklanmaydi: kadr faqat xotirada tekshirilib, darhol tashlanadi.
    private val VIDEO_SAMPLE_INTERVAL_MS = 15_000L
    private val MAX_VIDEO_SAMPLES = 12 // ~3 daqiqa uzluksiz kuzatish chegarasi

    private var videoWatchPackage: String? = null
    private var videoWatchCount: Int = 0
    private var videoWatchRunnable: Runnable? = null

    private fun ensureVideoWatch(packageName: String) {
        if (videoWatchPackage == packageName) return // allaqachon shu ilova uchun ishlab turibdi
        stopVideoWatch()
        videoWatchPackage = packageName
        videoWatchCount = 0
        scheduleVideoSample()
    }

    private fun stopVideoWatch() {
        videoWatchRunnable?.let { mainHandler.removeCallbacks(it) }
        videoWatchRunnable = null
        videoWatchPackage = null
        videoWatchCount = 0
    }

    private fun scheduleVideoSample() {
        val runnable = Runnable { runVideoFrameCheck() }
        videoWatchRunnable = runnable
        mainHandler.postDelayed(runnable, VIDEO_SAMPLE_INTERVAL_MS)
    }

    private fun runVideoFrameCheck() {
        val target = videoWatchPackage ?: return
        if (videoWatchCount >= MAX_VIDEO_SAMPLES) { stopVideoWatch(); return }
        if (captureRunning || !isScreenInteractive()) { stopVideoWatch(); return }
        val stillForeground = runCatching { rootInActiveWindow?.packageName?.toString() == target }.getOrDefault(false)
        if (!stillForeground) { stopVideoWatch(); return }

        videoWatchCount++
        captureRunning = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    screenshot.hardwareBuffer.close()
                    val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    if (bitmap == null) { captureRunning = false; rescheduleIfStillWatching(target); return }
                    bgExecutor.execute {
                        processVideoFrame(bitmap, target)
                        captureRunning = false
                        rescheduleIfStillWatching(target)
                    }
                } catch (t: Throwable) {
                    captureRunning = false
                    rescheduleIfStillWatching(target)
                }
            }
            override fun onFailure(errorCode: Int) {
                captureRunning = false
                rescheduleIfStillWatching(target)
            }
        })
    }

    private fun rescheduleIfStillWatching(target: String) {
        mainHandler.post { if (videoWatchPackage == target) scheduleVideoSample() }
    }

    /**
     * Video kadrini FAQAT tekshiradi. Xavf topilmasa — bitmap darhol
     * tashlanadi, HECH QANDAY fayl yozilmaydi, Firestore/Storage'ga
     * yuborilmaydi (bu qoida batareya va yozuv kvotasini tejashning
     * o'zagi). Xavf topilgandagina dalil saqlanadi/yuklanadi.
     */
    private fun processVideoFrame(bitmap: Bitmap, packageName: String) {
        val verdict = runCatching { MediaRiskAnalyzer.analyze(applicationContext, bitmap) }.getOrNull()
        if (verdict == null) { runCatching { bitmap.recycle() }; return }
        try {
            val now = System.currentTimeMillis()
            val appName = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
            FirebaseRepo.logRiskEvent(
                RiskEvent(
                    id = "risk_video_${now}_${packageName.hashCode()}", category = verdict.category,
                    severity = verdict.severity, confidence = verdict.confidence,
                    packageName = packageName, appName = appName, source = "video_frame_classifier",
                    summary = verdict.summary, contextText = "", mediaType = "VIDEO",
                    mediaState = if (verdict.sensitive) "HIDDEN_SENSITIVE" else "VISIBLE",
                    capturedAt = now, evidenceAvailable = true, sensitive = verdict.sensitive
                )
            )
            val file = File(cacheDir, "video_frame_${now}.jpg")
            val outputBitmap = if (verdict.sensitive) blurForEvidence(bitmap) else bitmap
            FileOutputStream(file).use { outputBitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (outputBitmap !== bitmap) outputBitmap.recycle()
            bitmap.recycle()
            val meta = ScreenshotMetadata(
                id = "${now}_video_${packageName.hashCode()}", childId = FirebaseRepo.childId.orEmpty(),
                familyId = FirebaseRepo.familyCode.orEmpty(), packageName = packageName, appLabel = label(packageName),
                capturedAt = now, date = todayKey(), dailyUsageSeconds = 0L, thresholdMinute = 0,
                riskCategory = verdict.category, sensitiveEvidence = verdict.sensitive
            )
            ScreenshotRepository.upload(file, meta) { file.delete() }
        } catch (t: Throwable) {
            runCatching { bitmap.recycle() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (settings.enabled && autoCaptureActive) scheduleAutoWatch()
        analyzeAccessibilityEvent(event)
    }

    private fun analyzeAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (packageName == applicationContext.packageName) return
        val parts = ArrayList<String>()
        event.text?.forEach { if (!it.isNullOrBlank()) parts.add(it.toString()) }
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        event.className?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        val videoFlag = booleanArrayOf(false)
        runCatching { rootInActiveWindow?.let { root -> collectVisibleText(root, parts, 0, videoFlag) } }

        // Video ko'rinishi (VideoView/PlayerView/...) aniqlansa — davriy kadr
        // tekshiruvini boshlaymiz (yoki davom ettiramiz, agar allaqachon shu
        // ilova uchun ishlab turgan bo'lsa). Boshqa ilovaga o'tilgan bo'lsa —
        // eski kuzatuvni to'xtatamiz (batareya/kvota tejash uchun muhim).
        if (videoFlag[0]) ensureVideoWatch(packageName)
        else if (videoWatchPackage != null && videoWatchPackage != packageName) stopVideoWatch()

        if (parts.isEmpty()) return
        val analysis = RiskAnalysisEngine.analyze(*parts.toTypedArray()) ?: return
        val mediaType = RiskAnalysisEngine.detectMediaMarker(parts)
        val now = System.currentTimeMillis()
        val dedupeKey = "${packageName}|${analysis.category}|${mediaType}|${analysis.summary}"
        val previous = recentRiskEvents[dedupeKey]
        recentRiskEvents.entries.removeAll { now - it.value > 60_000L }
        if (previous != null && now - previous < 60_000L) return
        recentRiskEvents[dedupeKey] = now
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val eventId = "risk_${now}_${dedupeKey.hashCode()}"
        val context = parts.joinToString(" ").replace(Regex("\\s+"), " ").take(1200)
        FirebaseRepo.logRiskEvent(
            RiskEvent(
                id = eventId, category = analysis.category, severity = analysis.severity,
                confidence = analysis.confidence, packageName = packageName, appName = appName,
                source = "accessibility", summary = analysis.summary, contextText = context,
                mediaType = mediaType,
                mediaState = if (mediaType.isBlank()) "NONE" else if (analysis.sensitive) "HIDDEN_SENSITIVE" else "VISIBLE",
                capturedAt = now, evidenceAvailable = analysis.shouldCaptureEvidence, sensitive = analysis.sensitive
            )
        )
        if (analysis.shouldCaptureEvidence && !analysis.sensitive && isScreenInteractive() && !captureRunning) {
            captureAndUpload(
                packageName = packageName, threshold = 0, usageSeconds = currentUsageSeconds(),
                key = eventId, remoteRequestId = null, requireUnlocked = true,
                riskCategory = analysis.category, sensitiveEvidence = false, onFinished = null
            )
        }
    }

    private val VIDEO_VIEW_HINTS = listOf(
        "VideoView", "PlayerView", "ExoPlayerView", "StyledPlayerView", "TextureView"
    )

    private fun collectVisibleText(
        node: android.view.accessibility.AccessibilityNodeInfo,
        out: MutableList<String>, depth: Int, videoFlag: BooleanArray
    ) {
        if (depth > 8) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.className?.toString()?.let { cls -> if (VIDEO_VIEW_HINTS.any { cls.contains(it) }) videoFlag[0] = true }
        for (i in 0 until node.childCount) {
            runCatching {
                node.getChild(i)?.let { child ->
                    collectVisibleText(child, out, depth + 1, videoFlag)
                    child.recycle()
                }
            }
        }
    }

    override fun onInterrupt(): Unit = Unit
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        settingsListener?.remove(); requestListener?.remove()
        clearBurst()
        stopVideoWatch()
        bgExecutor.shutdownNow()
        ScreenshotRepository.updateAccessibilityStatus(false)
        runCatching { unregisterReceiver(testReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_TEST_SCREENSHOT = "uz.oilanazorati.action.ACCESSIBILITY_SCREENSHOT_TEST"
        const val AUTO_WATCH_INTERVAL_MS = 30_000L
        const val AUTO_BURST_INTERVAL_MS = 60_000L
        const val AUTO_BURST_COUNT = 3
        const val LOCKED_SCREEN_MESSAGE = "📱 Bola qurilmasi ekranini uyg'otib bo'lmadi, shuning uchun screenshot olinmadi. Birozdan so'ng qayta urinib ko'ring."

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
