package uz.oilanazorati.parentcontrol.ui

import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.AmbientAudioRepository

/**
 * Ota-ona tomonidagi jonli ovoz — WebRTC transport.
 *
 * Ota-ona lokal mikrofon trekini yubormaydi, faqat bolaning audio trekini
 * qabul qiladi. WebRTC audio device module playback uchun saqlanadi.
 */
class AmbientListenActivity : AppCompatActivity() {
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private var requestListener: ListenerRegistration? = null
    private var sessionListener: ListenerRegistration? = null
    private var currentRequestId: String? = null
    private var stopping = false
    private var localCandCount = 0
    private var remoteCandCount = 0
    private var gotAnswer = false
    private val localCandTypes = mutableSetOf<String>()
    private val remoteCandTypes = mutableSetOf<String>()
    private fun candTypeOf(sdp: String): String {
        val m = Regex("typ (\\w+)").find(sdp)
        return m?.groupValues?.get(1) ?: "?"
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private val appliedChildCandidates = HashSet<String>()

    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashlytics.setCustomKey("webrtc_activity", "AmbientListenActivity")
        crashlytics.setCustomKey("webrtc_phase", "onCreate")
        crashlytics.log("WebRTC audio activity created")
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
        root.addView(title)
        root.addView(buildTag)
        root.addView(status)
        root.addView(startButton)
        root.addView(stopButton)
        setContentView(root)

        startButton.setOnClickListener { startListening() }
        stopButton.setOnClickListener { stopListening() }

        requestListener = AmbientAudioRepository.listenRequestStatus { _, state, _ ->
            runOnUiThread {
                if (currentRequestId == null) status.text = statusLabel(state)
            }
        }
    }

    private fun diag(phase: String, error: Throwable? = null) {
        crashlytics.setCustomKey("webrtc_phase", phase)
        crashlytics.log("WebRTC phase: $phase")
        if (error != null) {
            crashlytics.setCustomKey("webrtc_error", error.javaClass.name)
            crashlytics.setCustomKey("webrtc_error_message", error.message ?: "")
            crashlytics.recordException(error)
        }
    }

    private fun statusLabel(state: String?): String = when (state) {
        "requested" -> "⏳ Bola qurilmasidan kutilmoqda..."
        "active" -> "🔴 Jonli ovoz"
        "stopped" -> "To'xtatildi"
        "failed" -> "❌ Ovoz ulanmaydi"
        else -> "Tayyor"
    }

    private fun startListening() {
        if (currentRequestId != null) return
        stopping = false
        appliedChildCandidates.clear()
        localCandCount = 0
        remoteCandCount = 0
        gotAnswer = false
        localCandTypes.clear()
        remoteCandTypes.clear()
        status.text = "⏳ Ulanmoqda..."
        startButton.isEnabled = false
        stopButton.isEnabled = true
        currentRequestId = "pending"
        diag("startListening")

        try {
            diag("configure_audio_manager")
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = true
        } catch (t: Throwable) {
            diag("audio_manager_failed", t)
        }

        try {
            diag("initialize_webrtc")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions()
            )

            diag("create_audio_device_module")
            audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()

            diag("create_peer_connection_factory")
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()

            diag("create_peer_connection")
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                // TURN relay: STUN alone often fails when both devices are on mobile
                // data behind carrier-grade/symmetric NAT (common on 4G). TURN relays
                // the audio through a server so the call still connects.
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                    .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                    .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
            )

            peerConnection = factory?.createPeerConnection(
                PeerConnection.RTCConfiguration(iceServers),
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        crashlytics.setCustomKey("webrtc_ice_state", newState?.name ?: "null")
                        crashlytics.log("WebRTC ICE state: ${newState?.name}")
                        runOnUiThread {
                            when (newState) {
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> status.text = "🔴 Jonli ovoz"
                                PeerConnection.IceConnectionState.FAILED -> fail("WebRTC tarmoq ulanishi muvaffaqiyatsiz")
                                else -> Unit
                            }
                        }
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidate(candidate: IceCandidate) {
                        try {
                            crashlytics.log("WebRTC parent ICE candidate generated")
                            localCandCount++
                            localCandTypes.add(candTypeOf(candidate.sdp))
                            AmbientAudioRepository.sendParentIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                        } catch (t: Throwable) { diag("on_ice_candidate_exception", t) }
                    }
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                    override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
                        crashlytics.log("WebRTC remote audio track received via onAddTrack")
                        runOnUiThread { status.text = "🔴 Jonli ovoz" }
                    }
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        crashlytics.setCustomKey("webrtc_connection_state", newState?.name ?: "null")
                        crashlytics.log("WebRTC connection state: ${newState?.name}")
                        if (newState == PeerConnection.PeerConnectionState.FAILED) {
                            runOnUiThread { fail("WebRTC ulanishi muvaffaqiyatsiz") }
                        }
                    }
                    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                    override fun onTrack(transceiver: RtpTransceiver?) {
                        crashlytics.log("WebRTC remote track received via onTrack")
                        runOnUiThread { status.text = "🔴 Jonli ovoz" }
                    }
                }
            )

            if (peerConnection == null) throw IllegalStateException("WebRTC ulanish yaratilmadi")

            diag("add_recv_only_audio_transceiver")
            peerConnection!!.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )

            diag("create_offer")
            peerConnection!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    try {
                        if (desc == null) return runOnUiThread { fail("WebRTC taklif bo'sh") }
                        diag("set_local_description")
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(d: SessionDescription?) {}
                            override fun onSetSuccess() {
                                try {
                                    diag("send_offer")
                                    sendOffer(desc.description)
                                } catch (t: Throwable) { diag("send_offer_exception", t); runOnUiThread { fail(t.message) } }
                            }
                            override fun onCreateFailure(error: String?) { runOnUiThread { fail(error) } }
                            override fun onSetFailure(error: String?) { runOnUiThread { fail(error) } }
                        }, desc)
                    } catch (t: Throwable) { diag("set_local_description_exception", t); runOnUiThread { fail(t.message) } }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { runOnUiThread { fail(error) } }
                override fun onSetFailure(error: String?) { runOnUiThread { fail(error) } }
            }, MediaConstraints())
        } catch (t: Throwable) {
            diag("startListening_exception", t)
            fail(t.message ?: "WebRTC ishga tushmadi")
        }
    }

    private fun sendOffer(offerSdp: String) {
        diag("request_start_webrtc")
        AmbientAudioRepository.requestStartWebRtc(offerSdp) { ok, error, requestId ->
            runOnUiThread {
                try {
                    if (!ok || requestId == null) {
                        fail(error ?: "So'rov yuborilmadi")
                        return@runOnUiThread
                    }
                    currentRequestId = requestId
                    crashlytics.setCustomKey("webrtc_request_id", requestId)
                    status.text = "⏳ Bola qurilmasidan kutilmoqda..."
                    diag("listen_webrtc_session")
                    sessionListener = AmbientAudioRepository.listenWebRtcSession(
                        requestId,
                        onAnswer = { answerSdp ->
                            try {
                                gotAnswer = true
                                // Bu Firestore real-vaqt tinglovchisi — hujjatdagi keyingi HAR
                                // QANDAY o'zgarish (masalan navbatdagi ICE candidate) yana shu
                                // yerni chaqiradi, javob allaqachon qo'llanilgan bo'lsa ham.
                                // setRemoteDescription()ni ikkinchi marta chaqirish "Called in
                                // wrong state: stable" xatosini berardi. Faqat kutilayotgan
                                // holatda (hali javob qo'yilmagan) qo'llaymiz.
                                if (peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                                    diag("set_remote_description")
                                    peerConnection?.setRemoteDescription(object : SdpObserver {
                                        override fun onCreateSuccess(desc: SessionDescription?) {}
                                        override fun onSetSuccess() { runOnUiThread { status.text = "🔴 Jonli ovoz" } }
                                        override fun onCreateFailure(error: String?) { runOnUiThread { fail(error) } }
                                        override fun onSetFailure(error: String?) { runOnUiThread { fail(error) } }
                                    }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
                                }
                            } catch (t: Throwable) { diag("set_remote_description_exception", t); runOnUiThread { fail(t.message) } }
                        },
                        onChildCandidate = { candidate, mid, index ->
                            try {
                                if (appliedChildCandidates.add(candidate)) {
                                    crashlytics.log("WebRTC child ICE candidate received")
                                    remoteCandCount++
                                    remoteCandTypes.add(candTypeOf(candidate))
                                    peerConnection?.addIceCandidate(IceCandidate(mid, index, candidate))
                                }
                            } catch (t: Throwable) { diag("add_child_ice_exception", t) }
                        },
                        onStatus = { state, error ->
                            runOnUiThread {
                                try {
                                    when (state) {
                                        "active" -> status.text = "🔴 Jonli ovoz"
                                        "failed" -> fail(error ?: "Ovoz ulanmadi")
                                        "stopped" -> if (!stopping) resetUi("To'xtatildi")
                                    }
                                } catch (t: Throwable) { diag("session_status_exception", t); fail(t.message) }
                            }
                        }
                    )
                } catch (t: Throwable) { diag("send_offer_callback_exception", t); fail(t.message) }
            }
        }
    }

    private fun fail(message: String?) {
        crashlytics.setCustomKey("webrtc_failure_message", message ?: "Ulanmadi")
        crashlytics.log("WebRTC failure: ${message ?: "Ulanmadi"}")
        val diagSuffix = " [men:$localCandCount(${localCandTypes.joinToString(",")}) bola:$remoteCandCount(${remoteCandTypes.joinToString(",")}) javob:${if (gotAnswer) "bor" else "yo'q"}]"
        val text = "❌ ${message ?: "Ulanmadi"}$diagSuffix"
        resetUi(text)
    }

    private fun stopListening() {
        stopping = true
        diag("stopListening")
        status.text = "⏳ To'xtatilmoqda..."
        stopButton.isEnabled = false
        AmbientAudioRepository.requestStop()
        resetUi("To'xtatildi")
        stopping = false
    }

    private fun resetUi(message: String = "Tayyor") {
        diag("resetUi")
        sessionListener?.remove()
        sessionListener = null
        try { peerConnection?.close() } catch (t: Throwable) { diag("peer_close_exception", t) }
        try { peerConnection?.dispose() } catch (t: Throwable) { diag("peer_dispose_exception", t) }
        try { audioDeviceModule?.release() } catch (t: Throwable) { diag("audio_module_release_exception", t) }
        try { factory?.dispose() } catch (t: Throwable) { diag("factory_dispose_exception", t) }
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = false
        } catch (t: Throwable) { diag("audio_manager_reset_exception", t) }
        peerConnection = null
        audioDeviceModule = null
        factory = null
        currentRequestId = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        status.text = message
    }

    override fun onDestroy() {
        crashlytics.log("AmbientListenActivity onDestroy")
        if (currentRequestId != null && !stopping) AmbientAudioRepository.requestStop()
        requestListener?.remove()
        sessionListener?.remove()
        try { peerConnection?.close() } catch (t: Throwable) { diag("destroy_peer_close_exception", t) }
        try { peerConnection?.dispose() } catch (t: Throwable) { diag("destroy_peer_dispose_exception", t) }
        try { audioDeviceModule?.release() } catch (t: Throwable) { diag("destroy_audio_module_exception", t) }
        try { factory?.dispose() } catch (t: Throwable) { diag("destroy_factory_exception", t) }
        super.onDestroy()
    }
}