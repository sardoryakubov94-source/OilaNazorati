package uz.oilanazorati.parentcontrol.screenshot

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat
import uz.oilanazorati.parentcontrol.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var busy = false
    private var settings = ScreenshotSettings()
    private var settingsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private val handler = Handler(mainLooper)
    private val prefs by lazy { getSharedPreferences("screenshot_trigger_state", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settingsListener = ScreenshotRepository.listenSettings { settings = it }
        handler.post(evalRunnable)
    }

    private val evalRunnable = object : Runnable {
        override fun run() {
            evaluateAndCapture()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection == null && intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            if (resultCode == Activity.RESULT_OK && data != null) startProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification())
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { cleanupProjection() } }, handler)
    }

    private fun evaluateAndCapture() {
        if (projection == null || !settings.enabled || busy) return
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val start = cal.timeInMillis
        val now = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(start, now)
        val userStats = stats.filter { (pkg, s) -> pkg != packageName && s.totalTimeInForeground > 0 && (getApplicationInfoSafe(pkg)?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) ?: 0) == 0 }
        val auto = if (settings.autoTop3Enabled) userStats.entries.sortedByDescending { it.value.totalTimeInForeground }.take(3).map { it.key }.toSet() else emptySet()
        val targets = auto + settings.manualPackageNames.toSet()
        val current = currentForegroundPackage(usm, now) ?: return
        if (current !in targets) return
        val usageSec = (stats[current]?.totalTimeInForeground ?: 0L) / 1000L
        val frequency = settings.frequencyMinutes.coerceIn(15, 60)
        val minute = usageSec / 60L
        val base = (minute / frequency) * frequency
        if (base < frequency) return
        val candidates = listOf(base.toInt(), (base + 1).toInt(), (base + 2).toInt()).filter { minute >= it }
        val child = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId ?: return
        val date = todayKey()
        val threshold = candidates.firstOrNull { !prefs.getBoolean(triggerKey(child, current, date, it), false) } ?: return
        val key = triggerKey(child, current, date, threshold)
        ScreenshotRepository.reserveTrigger(key) { reserved ->
            if (reserved) capture(current, threshold, usageSec) { ok -> prefs.edit().putBoolean(key, ok).apply() }
        }
    }

    private fun todayKey() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun triggerKey(child: String, pkg: String, date: String, threshold: Int) = "${date}_${child.hashCode()}_${pkg.hashCode()}_$threshold"
    private fun currentForegroundPackage(usm: UsageStatsManager, now: Long): String? {
        val events = usm.queryEvents((now - 10 * 60_000L).coerceAtLeast(0L), now)
        val e = android.app.usage.UsageEvents.Event(); var latest: String? = null; var latestTs = 0L
        while (events.hasNextEvent()) { events.getNextEvent(e); if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && e.timeStamp >= latestTs) { latestTs = e.timeStamp; latest = e.packageName } }
        return latest
    }
    private fun getApplicationInfoSafe(pkg: String) = try { packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }

    private fun capture(packageName: String, threshold: Int, usageSeconds: Long, callback: (Boolean) -> Unit) {
        val mp = projection ?: return callback(false)
        if (busy) return callback(false)
        busy = true
        val dm = Resources.getSystem().displayMetrics
        val width = dm.widthPixels.coerceAtLeast(1); val height = dm.heightPixels.coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2); imageReader = reader
        virtualDisplay = mp.createVirtualDisplay("OilaNazoratiScreenshot", width, height, dm.densityDpi, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            var file: File? = null
            try {
                val plane = image.planes[0]; val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (bitmap.width != width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap
                file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (cropped !== bitmap) bitmap.recycle(); cropped.recycle()
                val meta = ScreenshotMetadata(
                    id = "${System.currentTimeMillis()}_${threshold}_${packageName.hashCode()}",
                    childId = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId.orEmpty(),
                    familyId = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode.orEmpty(),
                    packageName = packageName,
                    appLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() } catch (_: Exception) { packageName },
                    capturedAt = System.currentTimeMillis(), date = todayKey(), dailyUsageSeconds = usageSeconds, thresholdMinute = threshold
                )
                val uploadFile = file
                ScreenshotRepository.upload(uploadFile, meta) { ok -> uploadFile.delete(); callback(ok) }
            } catch (_: Exception) { file?.delete(); callback(false) }
            finally { image.close(); virtualDisplay?.release(); virtualDisplay = null; imageReader?.close(); imageReader = null; busy = false }
        }, handler)
    }

    private fun cleanupProjection() { virtualDisplay?.release(); virtualDisplay = null; imageReader?.close(); imageReader = null; projection = null; busy = false }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ekran nazorati", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_blank).setContentTitle("Oila Nazorati — ekran nazorati faol").setContentText("Ekran tasvirlari ota-ona sozlamalariga ko'ra olinadi").setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); settingsListener?.remove(); cleanupProjection(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "oila_nazorati_screen_capture"; const val NOTIFICATION_ID = 401
        const val ACTION_STOP = "uz.oilanazorati.parentcontrol.screenshot.STOP"
        const val EXTRA_RESULT_CODE = "result_code"; const val EXTRA_RESULT_DATA = "result_data"
    }
}
