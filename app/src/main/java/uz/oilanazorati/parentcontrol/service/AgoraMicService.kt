package uz.oilanazorati.parentcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * Ota-ona "Ovoz" funksiyasi — Agora orqali (WebRTC/io.github.webrtc-sdk
 * O'RNIGA, 3-oktabr). Qarang AmbientListenActivity.kt'dagi izoh — WebRTC'ning
 * native ishga tushish bosqichi BARCHA sinalgan qurilmalarda qulab tushardi.
 * Agora — millionlab qurilmada sinalgan, professional tarzda qo'llab-
 * quvvatlanadigan tayyor xizmat, shu muammoni chetlab o'tadi.
 *
 * MUHIM: Agora'da WebRTC'dagi kabi qo'lda SDP offer/answer yoki ICE candidate
 * almashish SHART EMAS — ikkala tomon ham shunchaki bir xil kanal nomiga
 * "kiradi" (joinChannel), qolgan hamma narsani Agora'ning serverlari
 * boshqaradi. Shu sabab bu versiya ancha sodda va kam xato ehtimolli.
 *
 * `transport == "agora"` tekshiruvi orqali veb-panelning WebRTC so'rovlariga
 * (WebRtcAmbientAudioService) aralashmaydi.
 */
class AgoraMicService : Service() {
    private val db = FirebaseFirestore.getInstance()
    private var requestListener: ListenerRegistration? = null
    private var engine: RtcEngine? = null
    private var activeRequestId: String? = null
    private var savedMusicVolume: Int? = null
    private var savedVoiceCallVolume: Int? = null
    private var savedAudioMode: Int? = null
    private var savedSpeakerphone: Boolean? = null
    private var audioStateSaved = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val maxSessionRunnable = Runnable { stopSession("stopped", "Vaqt limiti tugadi") }
    private val crashlytics = FirebaseCrashlytics.getInstance()

    companion object {
        const val CHANNEL_ID = "oila_nazorati_mic"
        const val NOTIFICATION_ID = 511
        const val MAX_SESSION_MS = 30 * 60 * 1000L
    }

    private val rtcHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            crashlytics.log("Agora: bola kanalga qo'shildi ($channel)")
            activeRequestId?.let { updateRequest(it, "active") }
        }

        override fun onError(err: Int) {
            crashlytics.setCustomKey("agora_error_code", err)
            activeRequestId?.let { updateRequest(it, "failed", "Agora xatosi: $err") }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            // Ota-ona chiqib ketdi — bola ham sessiyani yopadi.
            Handler(Looper.getMainLooper()).post { stopSession("stopped") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, readyNotification(), foregroundTypes())
        listenForRequests()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun foregroundTypes(): Int =
        if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    private fun listenForRequests() {
        val family = FirebaseRepo.familyCode ?: return
        val child = FirebaseRepo.childId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        requestListener = db.collection("families").document(family)
            .collection("children").document(child)
            .collection("mic_requests").document("current")
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
                val data = snap.data.orEmpty()
                if (data["transport"] != "agora") return@addSnapshotListener
                val requestId = data["requestId"] as? String ?: return@addSnapshotListener
                val channelName = data["channelName"] as? String
                when (data["status"] as? String) {
                    "requested" -> if (engine == null && channelName != null) startSession(requestId, channelName)
                    "stop_requested" -> if (activeRequestId == requestId) stopSession("stopped")
                }
            }
    }

    private fun startSession(requestId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            updateRequest(requestId, "failed", "Mikrofon ruxsati berilmagan")
            return
        }
        activeRequestId = requestId
        saveAudioState()
        updateActiveNotification()
        crashlytics.log("Agora: kanalga kirilmoqda ($channelName)")
        try {
            val config = RtcEngineConfig()
            config.mContext = applicationContext
            config.mAppId = AgoraConfig.APP_ID
            config.mEventHandler = rtcHandler
            config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            val rtc = RtcEngine.create(config)
            engine = rtc
            rtc.enableAudio()
            rtc.disableVideo()
            // Bola faqat mikrofon yuboradi; lokal playback yo'q.
            // Speaker routingni majburlamaymiz, chunki bu ayrim telefonlarda
            // tizim media ovozini pasaytirishi yoki earpiece rejimiga o'tkazishi mumkin.
            rtc.setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_GAME_STREAMING
            )
            rtc.adjustRecordingSignalVolume(100)
            val options = ChannelMediaOptions()
            options.channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            options.publishMicrophoneTrack = true
            options.autoSubscribeAudio = false
            options.autoSubscribeVideo = false
            val joinResult = rtc.joinChannel(null, channelName, 0, options)
            if (joinResult != Constants.ERR_OK) {
                throw IllegalStateException("Agora joinChannel failed: $joinResult")
            }
        } catch (t: Throwable) {
            crashlytics.recordException(t)
            stopSession("failed", t.message ?: "Agora ishga tushmadi")
        }
    }

    private fun stopSession(status: String, error: String? = null) {
        idleHandler.removeCallbacks(maxSessionRunnable)
        try {
            engine?.leaveChannel()
        } catch (_: Throwable) {}
        if (engine != null) { try { RtcEngine.destroy() } catch (_: Throwable) {} }
        engine = null
        restoreAudioState()
        val requestId = activeRequestId
        activeRequestId = null
        if (requestId != null) updateRequest(requestId, status, error)
        showReadyNotification()
    }

    private fun updateRequest(requestId: String, status: String, error: String? = null) {
        val family = FirebaseRepo.familyCode ?: return
        val child = FirebaseRepo.childId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val data = mutableMapOf<String, Any>("requestId" to requestId, "status" to status, "updatedAt" to System.currentTimeMillis())
        if (error != null) data["error"] = error.take(200)
        db.collection("families").document(family).collection("children").document(child)
            .collection("mic_requests").document("current").update(data)
        if (status == "active") idleHandler.postDelayed(maxSessionRunnable, MAX_SESSION_MS)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Ovoz nazorati", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun readyNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_blank)
        .setContentTitle("Oila Nazorati — Ovoz xizmati tayyor")
        .setContentText("Ovoz ota-ona jonli ovozni ishga tushirganda faollashadi")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true).setShowWhen(false).build()

    private fun activeNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_blank).setContentTitle("🎙️ Mikrofon faol").setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).setShowWhen(false).build()

    private fun updateActiveNotification() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, activeNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, activeNotification())
    }

    private fun showReadyNotification() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, readyNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, readyNotification())
    }

    private fun saveAudioState() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedVoiceCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            savedAudioMode = am.mode
            savedSpeakerphone = am.isSpeakerphoneOn
            audioStateSaved = true
        } catch (_: Throwable) {}
    }

    private fun restoreAudioState() {
        if (!audioStateSaved) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedMusicVolume?.let { am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
            savedVoiceCallVolume?.let { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) }
            savedAudioMode?.let { am.mode = it }
            savedSpeakerphone?.let { @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = it } }
        } catch (_: Throwable) {}
        savedMusicVolume = null
        savedVoiceCallVolume = null
        savedAudioMode = null
        savedSpeakerphone = null
        audioStateSaved = false
    }

    override fun onDestroy() {
        idleHandler.removeCallbacksAndMessages(null)
        requestListener?.remove()
        try { engine?.leaveChannel() } catch (_: Throwable) {}
        try { RtcEngine.destroy() } catch (_: Throwable) {}
        engine = null
        restoreAudioState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
