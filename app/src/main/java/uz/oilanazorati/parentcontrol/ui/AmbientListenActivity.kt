package uz.oilanazorati.parentcontrol.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import uz.oilanazorati.parentcontrol.service.AgoraConfig
import uz.oilanazorati.parentcontrol.util.VoiceLimit
import java.util.UUID

/**
 * Ota-ona tomonidagi jonli ovoz — Agora orqali (3-oktabr, WebRTC O'RNIGA).
 *
 * MUHIM TARIXIY ESLATMA: bu funksiya avval (8-sentabrdan buyon) WebRTC
 * (io.github.webrtc-sdk) orqali ishlagan, lekin native ishga tushirish
 * bosqichida BARCHA sinalgan qurilmalarda, kutubxona versiyasidan qat'i
 * nazar, SIGTRAP bilan qulab tushardi — sabab aniqlanmadi (ruxsat, R8,
 * crashlytics-ndk, TensorFlow Lite — hech biri yordam bermadi). Keyin
 * oddiy Firestore+AudioTrack usuliga qaytarildi (ishladi, lekin Firebase
 * kvotasini ko'p sarflardi). Endi Agora — millionlab qurilmada sinalgan,
 * professional xizmat — ishlatilmoqda: past kechikish (WebRTC darajasida),
 * lekin signalizatsiya (SDP/ICE) butunlay Agora serverlarida, bizning
 * kodimizda emas — shu sabab avvalgi crash turi bu yerda takrorlanishi
 * deyarli mumkin emas.
 */
class AmbientListenActivity : AppCompatActivity() {
    private val USAGE_FLUSH_SECONDS = 60
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var requestListener: ListenerRegistration? = null
    private var engine: RtcEngine? = null
    private var currentRequestId: String? = null
    private var stopping = false

    private lateinit var status: TextView
    private lateinit var limitInfo: TextView

    // Kunlik limit (30 daqiqa). Adminlar uchun cheklov yo'q.
    private var isAdminUser = false
    private var remainingAtStart = 0
    private var connectedAtMs = 0L
    private var flushedSeconds = 0
    // Sarfni o'qimasdan yozish uchun: sessiya boshidagi sarf, kun va kun almashgandagi boshlanish nuqtasi
    private var usageDay = ""
    private var usedBase = 0
    private var segmentStartElapsed = 0
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            if (connectedAtMs != 0L) ticker.postDelayed(this, 1000)
        }
    }
    private lateinit var waveform: AudioWaveformView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val rtcHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            crashlytics.log("Agora parent: joined channel=$channel uid=$uid")
            runOnUiThread {
                if (currentRequestId != null) {
                    status.text = "⏳ Ovoz kanali ulandi, bola kutilmoqda..."
                }
            }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            try {
                engine?.muteRemoteAudioStream(uid, false)
                engine?.adjustUserPlaybackSignalVolume(uid, 100)
                engine?.adjustPlaybackSignalVolume(100)
                engine?.setDefaultAudioRoutetoSpeakerphone(true)
                engine?.setEnableSpeakerphone(true)
            } catch (t: Throwable) {
                crashlytics.recordException(t)
            }
            runOnUiThread {
                status.text = "🔴 Jonli ovoz"
                waveform.setActive(true)
                startCounting()
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            runOnUiThread { if (currentRequestId != null) resetUi("To'xtatildi") }
        }

        override fun onError(err: Int) {
            crashlytics.setCustomKey("agora_parent_error_code", err)
            crashlytics.log("Agora parent: onError=$err")
            runOnUiThread {
                if (currentRequestId != null) {
                    stopCounting()
                    status.text = "❌ Agora xatosi: $err"
                    waveform.setActive(false)
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    requestListener?.remove()
                    requestListener = null
                    try { engine?.leaveChannel() } catch (_: Throwable) {}
                    if (engine != null) { try { RtcEngine.destroy() } catch (_: Throwable) {} }
                    engine = null
                    currentRequestId = null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashlytics.setCustomKey("ovoz_activity", "AmbientListenActivity")
        crashlytics.setCustomKey("ovoz_transport", "agora")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(getColor(R.color.color_bg))
        }
        val title = TextView(this).apply {
            text = "🎙️ Ovoz"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.color_text_primary))
        }
        status = TextView(this).apply {
            text = "Tayyor"
            textSize = 14f
            setPadding(0, 18, 0, 18)
            setTextColor(getColor(R.color.color_text_secondary))
        }
        val buildTag = TextView(this).apply {
            text = "build ${uz.oilanazorati.parentcontrol.BuildConfig.BUILD_TAG}"
            textSize = 11f
            setTextColor(getColor(R.color.color_text_secondary))
        }
        startButton = Button(this).apply {
            text = "Eshitishni boshlash"
            setTextColor(getColor(R.color.color_text_primary))
            setBackgroundResource(R.drawable.bg_card_theme)
            val icon = ContextCompat.getDrawable(this@AmbientListenActivity, R.drawable.ic_play)?.mutate()
            icon?.setColorFilter(currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = 16
        }
        stopButton = Button(this).apply {
            text = "To'xtatish"
            isEnabled = false
            setTextColor(getColor(R.color.color_text_primary))
            setBackgroundResource(R.drawable.bg_card_theme)
            val icon = ContextCompat.getDrawable(this@AmbientListenActivity, R.drawable.ic_stop)?.mutate()
            icon?.setColorFilter(currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = 16
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (16 * resources.displayMetrics.density).toInt()
            }
        }
        limitInfo = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 12)
            setTextColor(getColor(R.color.color_text_secondary))
        }
        waveform = AudioWaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(title)
        root.addView(buildTag)
        root.addView(status)
        root.addView(limitInfo)
        root.addView(waveform)
        root.addView(startButton)
        root.addView(stopButton)
        setContentView(root)

        startButton.setOnClickListener {
            FirebaseRepo.checkIsPremium { isPremium ->
                if (!isPremium) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("⭐ Premium kerak")
                        .setMessage("Jonli ovoz eshitish faqat premium foydalanuvchilar uchun mavjud.")
                        .setPositiveButton("Premiumni ochish") { _, _ -> startActivity(android.content.Intent(this, PremiumActivity::class.java)) }
                        .setNegativeButton("Bekor qilish", null)
                        .show()
                    return@checkIsPremium
                }
                VoiceLimit.load { admin, remaining ->
                    if (remaining == null) {
                        status.text = "⚠️ Limitni tekshirib bo'lmadi. Internetni tekshirib, qayta urinib ko'ring"
                        return@load
                    }
                    isAdminUser = admin
                    remainingAtStart = remaining
                    usageDay = VoiceLimit.dayKey()
                    usedBase = if (admin) 0 else VoiceLimit.DAILY_SECONDS - remaining
                    segmentStartElapsed = 0
                    if (!admin && remaining <= 0) {
                        status.text = "⏳ Bugungi 30 daqiqalik limit tugadi. Ertaga qayta tiklanadi"
                        limitInfo.text = "⏱ Bugun qoldi: 00:00 / ${VoiceLimit.format(VoiceLimit.DAILY_SECONDS)}"
                        return@load
                    }
                    startListening()
                }
            }
        }
        stopButton.setOnClickListener { stopListening() }
        refreshLimitInfo()
    }

    private fun refreshLimitInfo() {
        VoiceLimit.load { admin, remaining ->
            isAdminUser = admin
            limitInfo.text = when {
                admin -> "♾️ Cheksiz (admin)"
                remaining == null -> ""
                else -> "⏱ Bugun qoldi: ${VoiceLimit.format(remaining)} / ${VoiceLimit.format(VoiceLimit.DAILY_SECONDS)}"
            }
        }
    }

    /** Bola ovozi kelgan paytdan boshlab vaqt hisoblanadi. */
    private fun startCounting() {
        if (connectedAtMs != 0L) return
        connectedAtMs = SystemClock.elapsedRealtime()
        flushedSeconds = 0
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    private fun elapsedSeconds(): Int =
        if (connectedAtMs == 0L) 0 else ((SystemClock.elapsedRealtime() - connectedAtMs) / 1000).toInt()

    private fun onTick() {
        if (currentRequestId == null || connectedAtMs == 0L) return
        if (isAdminUser) {
            status.text = "🔴 Jonli ovoz · ♾️ Cheksiz (admin)"
            return
        }
        val elapsed = elapsedSeconds()
        val left = remainingAtStart - elapsed
        status.text = "🔴 Jonli ovoz · ⏱ ${VoiceLimit.format(left)}"
        if (elapsed - flushedSeconds >= USAGE_FLUSH_SECONDS) {
            flushUsage(elapsed)
            flushedSeconds = elapsed
        }
        if (left <= 0) stopListening("⏳ Kunlik 30 daqiqalik limit tugadi. Ertaga qayta tiklanadi")
    }

    /** Jami sarfni BITTA yozuv bilan saqlaydi. Kun almashgan bo'lsa, yangi kundan boshlaydi. */
    private fun flushUsage(elapsed: Int) {
        val today = VoiceLimit.dayKey()
        if (today != usageDay) {
            usageDay = today
            usedBase = 0
            segmentStartElapsed = flushedSeconds
        }
        VoiceLimit.setUsage(usedBase + (elapsed - segmentStartElapsed).coerceAtLeast(0))
    }

    /** Sanashni to'xtatadi va saqlanmagan soniyalarni yozadi. */
    private fun stopCounting() {
        ticker.removeCallbacks(tick)
        if (!isAdminUser && connectedAtMs != 0L) {
            val rest = elapsedSeconds() - flushedSeconds
            if (rest > 0) flushUsage(elapsedSeconds())
        }
        connectedAtMs = 0L
        flushedSeconds = 0
    }

    private fun familyAndChild(): Pair<String, String>? {
        val family = FirebaseRepo.familyCode ?: return null
        val child = FirebaseRepo.childId ?: return null
        return family to child
    }

    private fun requestDoc() = familyAndChild()?.let { (family, child) ->
        db.collection("families").document(family).collection("children").document(child)
            .collection("mic_requests").document("current")
    }

    private fun startListening() {
        if (currentRequestId != null) return
        val doc = requestDoc() ?: run { status.text = "❌ Oila/bola aniqlanmadi"; return }
        stopping = false
        val requestId = "req_${System.currentTimeMillis()}"
        val channelName = "oz_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        currentRequestId = requestId
        startButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "⏳ Bola qurilmasidan kutilmoqda..."
        crashlytics.log("Ovoz (Agora): so'rov yuborilmoqda")

        requestListener?.remove()
        requestListener = doc.addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists() || currentRequestId != requestId) return@addSnapshotListener
            val data = snap.data.orEmpty()
            if (data["requestId"] != requestId) return@addSnapshotListener
            when (data["status"] as? String) {
                "failed" -> runOnUiThread {
                    status.text = "❌ ${data["error"] as? String ?: "Mikrofonni ulab bo'lmadi"}"
                    resetUi()
                }
            }
        }

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
            rtc.enableLocalAudio(false) // ota-ona mikrofonni ishlatmaydi, faqat eshitadi
            // Android/Agora Communication rejimida ayrim qurilmalarda
            // ovoz earpiece ga ketib qolishi mumkin. Ota-ona tomoni doim
            // telefonning asosiy karnayidan eshitsin.
            rtc.setDefaultAudioRoutetoSpeakerphone(true)
            rtc.setEnableSpeakerphone(true)
            rtc.setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_GAME_STREAMING
            )
            rtc.adjustPlaybackSignalVolume(100)
            val options = ChannelMediaOptions()
            options.channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            options.clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            options.publishMicrophoneTrack = false
            options.autoSubscribeAudio = true
            options.autoSubscribeVideo = false

            doc.set(mapOf(
                "requestId" to requestId,
                "status" to "requested",
                "transport" to "agora",
                "channelName" to channelName,
                "requestedByUid" to (FirebaseAuth.getInstance().currentUser?.uid ?: ""),
                "updatedAt" to System.currentTimeMillis()
            )).addOnFailureListener { e ->
                crashlytics.recordException(e)
                runOnUiThread {
                    status.text = "❌ So'rov yuborilmadi"
                    resetUi()
                }
            }

            val joinResult = rtc.joinChannel(AgoraConfig.token(channelName), channelName, 0, options)
            if (joinResult != Constants.ERR_OK) {
                throw IllegalStateException("Agora joinChannel failed: $joinResult")
            }
        } catch (t: Throwable) {
            crashlytics.recordException(t)
            status.text = "❌ Ulanishda xato"
            resetUi()
        }
    }

    private fun stopListening(message: String = "To'xtatildi") {
        stopping = true
        val requestId = currentRequestId
        status.text = "⏳ To'xtatilmoqda..."
        stopButton.isEnabled = false
        if (requestId != null) {
            requestDoc()?.update(mapOf("status" to "stop_requested", "requestId" to requestId, "updatedAt" to System.currentTimeMillis()))
        }
        resetUi(message)
        stopping = false
    }

    private fun resetUi(message: String = "Tayyor") {
        stopCounting()
        ticker.postDelayed({ refreshLimitInfo() }, 1500)
        waveform.setActive(false)
        requestListener?.remove()
        requestListener = null
        try { engine?.leaveChannel() } catch (_: Throwable) {}
        if (engine != null) { try { RtcEngine.destroy() } catch (_: Throwable) {} }
        engine = null
        currentRequestId = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        status.text = message
    }

    override fun onDestroy() {
        stopCounting()
        if (currentRequestId != null && !stopping) {
            requestDoc()?.update(mapOf("status" to "stop_requested", "updatedAt" to System.currentTimeMillis()))
        }
        requestListener?.remove()
        try { engine?.leaveChannel() } catch (_: Throwable) {}
        if (engine != null) { try { RtcEngine.destroy() } catch (_: Throwable) {} }
        engine = null
        super.onDestroy()
    }
}
