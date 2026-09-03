package uz.oilanazorati.parentcontrol.screenshot

import android.app.*
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import uz.oilanazorati.parentcontrol.R
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var busy = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_CAPTURE) {
            capture(intent.getStringExtra(EXTRA_PACKAGE).orEmpty(), intent.getIntExtra(EXTRA_THRESHOLD, 0), intent.getLongExtra(EXTRA_USAGE, 0L))
            return START_STICKY
        }
        if (projection == null && intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultCode == Activity.RESULT_OK && data != null) startProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification())
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { cleanupProjection() }
        }, android.os.Handler(mainLooper))
    }

    private fun capture(packageName: String, threshold: Int, usageSeconds: Long) {
        val mp = projection ?: return
        if (busy) return
        busy = true
        val dm = Resources.getSystem().displayMetrics
        val width = dm.widthPixels.coerceAtLeast(1)
        val height = dm.heightPixels.coerceAtLeast(1)
        val density = dm.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay("OilaNazoratiScreenshot", width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null)
        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (bitmap.width != width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap
                val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (cropped !== bitmap) bitmap.recycle()
                cropped.recycle()
                val meta = ScreenshotMetadata(
                    id = "${System.currentTimeMillis()}_${threshold}_${packageName.hashCode()}",
                    childId = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId.orEmpty(),
                    familyId = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode.orEmpty(),
                    packageName = packageName,
                    appLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() } catch (_: Exception) { packageName },
                    capturedAt = System.currentTimeMillis(),
                    date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()),
                    dailyUsageSeconds = usageSeconds,
                    thresholdMinute = threshold
                )
                ScreenshotRepository.upload(file, meta) { file.delete() }
            } catch (_: Exception) { }
            finally { image.close(); virtualDisplay?.release(); virtualDisplay = null; imageReader?.close(); imageReader = null; busy = false }
        }, android.os.Handler(mainLooper))
    }

    private fun cleanupProjection() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection = null
        busy = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Ekran nazorati", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_blank)
        .setContentTitle("Oila Nazorati — ekran nazorati faol")
        .setContentText("Ekran tasvirlari ota-ona sozlamalariga ko'ra olinadi")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    override fun onDestroy() { cleanupProjection(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "oila_nazorati_screen_capture"
        const val NOTIFICATION_ID = 401
        const val ACTION_CAPTURE = "uz.oilanazorati.parentcontrol.screenshot.CAPTURE"
        const val ACTION_STOP = "uz.oilanazorati.parentcontrol.screenshot.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_THRESHOLD = "threshold_minute"
        const val EXTRA_USAGE = "usage_seconds"
    }
}
