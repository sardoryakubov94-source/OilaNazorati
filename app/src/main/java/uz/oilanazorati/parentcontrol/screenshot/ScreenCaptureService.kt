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
import android.os.Looper
import androidx.core.app.NotificationCompat
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.ScreenshotMetadata
import uz.oilanazorati.parentcontrol.model.ScreenshotSettings
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var settings = ScreenshotSettings()
    private var settingsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var requestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("screenshot_trigger_state", MODE_PRIVATE) }
    private var pending: PendingCapture? = null
    private var captureInProgress = false
    private var waitingRemoteRequestId: String? = null
    private val pollRunnable = object : Runnable { override fun run() { if (pending == null || captureInProgress) return; val image = imageReader?.acquireLatestImage(); if (image != null) processImage(image) else handler.postDelayed(this, CAPTURE_POLL_INTERVAL_MS) } }
    private val captureTimeoutRunnable = Runnable { onCaptureTimeout() }
    private val evalRunnable = object : Runnable { override fun run() { if (!AccessibilityScreenshotService.isServiceEnabled(this@ScreenCaptureService)) evaluateAndQueue(); handler.postDelayed(this, 30_000L) } }

    data class PendingCapture(val packageName: String, val threshold: Int, val usageSeconds: Long, val key: String, val remoteRequestId: String? = null)

    override fun onCreate() {
        super.onCreate()
        // Android requires the foreground notification to be established immediately
        // after startForegroundService(). Do not perform Firebase/other initialization first.
        startForegroundNow()
        createNotificationChannel()
        settingsListener = ScreenshotRepository.listenSettings { newSettings ->
            settings = newSettings
            if (!newSettings.enabled) {
                waitingRemoteRequestId = null
                cleanupProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        ensureRequestListener()
        handler.post(evalRunnable)
        ScreenshotRepository.updateProjectionStatus(false)
    }

    private fun startForegroundNow() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    private fun ensureRequestListener() {
        if (requestListener != null) return
        requestListener = ScreenshotRepository.listenScreenshotRequests { requestId ->
            if (!settings.enabled || AccessibilityScreenshotService.isServiceEnabled(this)) return@listenScreenshotRequests
            if (projection == null) waitingRemoteRequestId = requestId else queueRemoteCapture(requestId)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { cleanupProjection(); stopSelf(); return START_NOT_STICKY }
        if (AccessibilityScreenshotService.isServiceEnabled(this)) { cleanupProjection(); stopSelf(); return START_NOT_STICKY }
        if (!settings.enabled) return START_NOT_STICKY
        if (projection == null && intent != null) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            if (code == Activity.RESULT_OK && data != null) startProjection(code, data)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (!settings.enabled || AccessibilityScreenshotService.isServiceEnabled(this)) return
        val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
        try {
            // Keep the service in the foreground before initializing MediaProjection.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIFICATION_ID, notification())
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data) ?: run { stopSelf(); return }
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { cleanupProjection(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }, handler)
            val dm = Resources.getSystem().displayMetrics
            imageReader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
            imageReader!!.setOnImageAvailableListener({ reader -> handleFrame(reader) }, handler)
            virtualDisplay = projection!!.createVirtualDisplay("OilaNazoratiScreenshot", dm.widthPixels, dm.heightPixels, dm.densityDpi, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, handler)
            hasLiveProjection = true; ScreenshotRepository.updateProjectionStatus(true)
            waitingRemoteRequestId?.let { waitingRemoteRequestId = null; queueRemoteCapture(it) }
        } catch (e: Throwable) { crashlytics.recordException(e); cleanupProjection(); stopSelf() }
    }

    private fun queueRemoteCapture(requestId: String) {
        if (!settings.enabled || AccessibilityScreenshotService.isServiceEnabled(this) || pending != null || captureInProgress || projection == null) return
        val now = System.currentTimeMillis(); val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager
        val current = if (usm != null) currentForegroundPackage(usm, now) else null
        val usageSec = if (usm != null && current != null) { val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0) }.timeInMillis; (usm.queryAndAggregateUsageStats(start, now)[current]?.totalTimeInForeground ?: 0L)/1000L } else 0L
        pending = PendingCapture(current ?: "uz.oilanazorati.screen", 0, usageSec, "remote_$requestId", requestId)
        ScreenshotRepository.markScreenshotRequest(requestId, "processing"); schedulePendingCaptureWatch()
    }

    private fun evaluateAndQueue() {
        if (!settings.enabled || AccessibilityScreenshotService.isServiceEnabled(this) || projection == null || pending != null || captureInProgress) return
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return; val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0) }; val now=System.currentTimeMillis(); val stats=usm.queryAndAggregateUsageStats(cal.timeInMillis,now)
        val userStats=stats.filter { (pkg,s)->pkg!=packageName&&s.totalTimeInForeground>0&&(getApplicationInfoSafe(pkg)?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM)?:0)==0 }
        val auto=if(settings.autoTop3Enabled) userStats.entries.sortedByDescending{it.value.totalTimeInForeground}.take(3).map{it.key}.toSet() else emptySet(); val targets=auto+settings.manualPackageNames.toSet(); val current=currentForegroundPackage(usm,now)?:return; if(current !in targets)return
        val usageSec=(stats[current]?.totalTimeInForeground?:0L)/1000L; val frequency=settings.frequencyMinutes.coerceIn(15,60).toLong(); val minute=usageSec/60L; val base=(minute/frequency)*frequency; if(base<frequency)return
        val child=uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId?:return; val date=todayKey(); val threshold=listOf(base.toInt(),(base+1).toInt(),(base+2).toInt()).filter{minute>=it}.firstOrNull{!prefs.getBoolean(triggerKey(child,current,date,it),false)}?:return; val key=triggerKey(child,current,date,threshold)
        ScreenshotRepository.reserveTrigger(key){reserved->if(reserved){pending=PendingCapture(current,threshold,usageSec,key);schedulePendingCaptureWatch()}}
    }

    private fun handleFrame(reader: ImageReader){if(!settings.enabled||AccessibilityScreenshotService.isServiceEnabled(this)){cleanupProjection();return};if(pending==null||captureInProgress)return;val image=reader.acquireLatestImage()?:return;processImage(image)}
    private fun onCaptureTimeout(){val request=pending?:return;if(captureInProgress)return;cancelPendingCaptureWatch();if(request.remoteRequestId!=null)ScreenshotRepository.markScreenshotRequest(request.remoteRequestId,"failed","Ekran surati vaqtida olinmadi") else prefs.edit().putBoolean(request.key,false).apply();pending=null}
    private fun schedulePendingCaptureWatch(){handler.removeCallbacks(pollRunnable);handler.removeCallbacks(captureTimeoutRunnable);handler.postDelayed(captureTimeoutRunnable,CAPTURE_TIMEOUT_MS);handler.postDelayed(pollRunnable,CAPTURE_POLL_INTERVAL_MS)}
    private fun cancelPendingCaptureWatch(){handler.removeCallbacks(pollRunnable);handler.removeCallbacks(captureTimeoutRunnable)}

    private fun processImage(image: android.media.Image){if(!settings.enabled||AccessibilityScreenshotService.isServiceEnabled(this)){image.close();cleanupProjection();return};val request=pending?:run{image.close();return};captureInProgress=true;cancelPendingCaptureWatch();var file:File?=null;try{val dm=Resources.getSystem().displayMetrics;val plane=image.planes[0];val pixel=plane.pixelStride;val row=plane.rowStride;val padding=row-pixel*dm.widthPixels;val bitmap=Bitmap.createBitmap(dm.widthPixels+padding/pixel,dm.heightPixels,Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(plane.buffer);val cropped=if(bitmap.width!=dm.widthPixels)Bitmap.createBitmap(bitmap,0,0,dm.widthPixels,dm.heightPixels)else bitmap;file=File(cacheDir,"screenshot_${System.currentTimeMillis()}.jpg");FileOutputStream(file).use{cropped.compress(Bitmap.CompressFormat.JPEG,82,it)};if(cropped!==bitmap)bitmap.recycle();cropped.recycle();val meta=ScreenshotMetadata("${System.currentTimeMillis()}_${request.threshold}_${request.packageName.hashCode()}",uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId.orEmpty(),uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode.orEmpty(),request.packageName,label(request.packageName),System.currentTimeMillis(),todayKey(),request.usageSeconds,request.threshold);val upload=file;ScreenshotRepository.upload(upload,meta){ok->upload.delete();if(request.remoteRequestId!=null)ScreenshotRepository.markScreenshotRequest(request.remoteRequestId,if(ok)"completed" else "failed",if(ok)"Screenshot tayyor" else "Screenshot yuklanmadi")else prefs.edit().putBoolean(request.key,ok).apply();pending=null;captureInProgress=false}}
        catch(e:Throwable){file?.delete();if(request.remoteRequestId!=null)ScreenshotRepository.markScreenshotRequest(request.remoteRequestId,"failed",e.message?:"capture error")else prefs.edit().putBoolean(request.key,false).apply();crashlyticsRecord(e);pending=null;captureInProgress=false}finally{image.close()}}
    private fun crashlyticsRecord(e:Throwable)=com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(e)
    private fun label(pkg:String)=try{packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString()}catch(_:Exception){pkg}
    private fun currentForegroundPackage(usm:UsageStatsManager,now:Long):String?{val ev=usm.queryEvents((now-10*60_000L).coerceAtLeast(0L),now);val e=android.app.usage.UsageEvents.Event();var p:String?=null;var t=0L;while(ev.hasNextEvent()){ev.getNextEvent(e);if(e.eventType==android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND&&e.timeStamp>=t){t=e.timeStamp;p=e.packageName}};return p}
    private fun getApplicationInfoSafe(pkg:String)=try{packageManager.getApplicationInfo(pkg,0)}catch(_:Exception){null}
    private fun todayKey()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
    private fun triggerKey(child:String,pkg:String,date:String,threshold:Int)="${date}_${child.hashCode()}_${pkg.hashCode()}_$threshold"
    private fun cleanupProjection(){cancelPendingCaptureWatch();if(hasLiveProjection)ScreenshotRepository.updateProjectionStatus(false);hasLiveProjection=false;virtualDisplay?.release();virtualDisplay=null;imageReader?.close();imageReader=null;projection?.stop();projection=null;pending=null;captureInProgress=false}
    private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Ekran nazorati",NotificationManager.IMPORTANCE_LOW))}
    private fun notification()=NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_blank).setContentTitle("Oila Nazorati — ekran nazorati faol").setContentText("Ekran tasvirlari ota-ona sozlamalariga ko'ra olinadi").setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);settingsListener?.remove();requestListener?.remove();cleanupProjection();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{const val CHANNEL_ID="oila_nazorati_screen_capture";const val NOTIFICATION_ID=401;const val ACTION_STOP="uz.oilanazorati.parentcontrol.screenshot.STOP";const val EXTRA_RESULT_CODE="result_code";const val EXTRA_RESULT_DATA="result_data";const val CAPTURE_TIMEOUT_MS=8_000L;const val CAPTURE_POLL_INTERVAL_MS=500L;@Volatile var hasLiveProjection=false}
}