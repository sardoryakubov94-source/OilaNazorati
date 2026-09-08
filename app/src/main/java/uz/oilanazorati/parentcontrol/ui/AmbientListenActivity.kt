package uz.oilanazorati.parentcontrol.ui

import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.AudioDeviceModule
import org.webrtc.IceCandidate
import org.webrtc.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.AmbientAudioRepository

/**
 * Ota-ona tomonidagi jonli ovoz — WebRTC transport.
 *
 * Ota-ona lokal mikrofon trekini yubormaydi, faqat bolaning audio trekini
 * qabul qiladi. WebRTC audio device module playback uchun saqlanadi.
 * Firestore faqat WebRTC signalizatsiyasi uchun ishlatiladi.
 */
class AmbientListenActivity : AppCompatActivity() {
    private var requestListener: ListenerRegistration? = null
    private var sessionListener: ListenerRegistration? = null
    private var currentRequestId: String? = null
    private var stopping = false

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private val appliedChildCandidates = HashSet<String>()

    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun statusLabel(state: String?): String = when (state) {
        "requested" -> "⏳ Bola qurilmasidan kutilmoqda..."
        "active" -> "🔴 Jonli ovoz"
        "stopped" -> "To'xtatildi"
        "failed" -> "❌ Ovoz ulanmadi"
        else -> "Tayyor"
    }

    private fun startListening() {
        if (currentRequestId != null) return
        stopping = false
        appliedChildCandidates.clear()
        status.text = "⏳ Ulanmoqda..."
        startButton.isEnabled = false
        stopButton.isEnabled = true
        currentRequestId = "pending"

        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = true
        } catch (_: Throwable) {}

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions()
            )

            // WebRTC ADM is required for reliable audio playback on the parent.
            // It does not create or publish a local audio track in this activity.
            audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()

            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            peerConnection = factory?.createPeerConnection(
                PeerConnection.RTCConfiguration(iceServers),
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
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
                        AmbientAudioRepository.sendParentIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                    }
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                    override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
                        runOnUiThread { status.text = "🔴 Jonli ovoz" }
                    }
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        if (newState == PeerConnection.PeerConnectionState.FAILED) {
                            runOnUiThread { fail("WebRTC ulanishi muvaffaqiyatsiz") }
                        }
                    }
                    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                    override fun onTrack(transceiver: RtpTransceiver?) {
                        runOnUiThread { status.text = "🔴 Jonli ovoz" }
                    }
                }
            )

            if (peerConnection == null) throw IllegalStateException("WebRTC ulanish yaratilmadi")

            peerConnection!!.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )

            peerConnection!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return runOnUiThread { fail("WebRTC taklif bo'sh") }
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(d: SessionDescription?) {}
                        override fun onSetSuccess() { sendOffer(desc.description) }
                        override fun onCreateFailure(error: String?) { runOnUiThread { fail(error) } }
                        override fun onSetFailure(error: String?) { runOnUiThread { fail(error) } }
                    }, desc)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { runOnUiThread { fail(error) } }
                override fun onSetFailure(error: String?) { runOnUiThread { fail(error) } }
            }, MediaConstraints())
        } catch (t: Throwable) {
            fail(t.message ?: "WebRTC ishga tushmadi")
        }
    }

    private fun sendOffer(offerSdp: String) {
        AmbientAudioRepository.requestStartWebRtc(offerSdp) { ok, error, requestId ->
            runOnUiThread {
                try {
                    if (!ok || requestId == null) {
                        fail(error ?: "So'rov yuborilmadi")
                        return@runOnUiThread
                    }
                    currentRequestId = requestId
                    status.text = "⏳ Bola qurilmasidan kutilmoqda..."
                    sessionListener = AmbientAudioRepository.listenWebRtcSession(
                        requestId,
                        onAnswer = { answerSdp ->
                            try {
                                peerConnection?.setRemoteDescription(object : SdpObserver {
                                    override fun onCreateSuccess(desc: SessionDescription?) {}
                                    override fun onSetSuccess() { runOnUiThread { status.text = "🔴 Jonli ovoz" } }
                                    override fun onCreateFailure(error: String?) { runOnUiThread { fail(error) } }
                                    override fun onSetFailure(error: String?) { runOnUiThread { fail(error) } }
                                }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
                            } catch (t: Throwable) { runOnUiThread { fail(t.message) } }
                        },
                        onChildCandidate = { candidate, mid, index ->
                            try {
                                if (appliedChildCandidates.add(candidate)) {
                                    peerConnection?.addIceCandidate(IceCandidate(mid, index, candidate))
                                }
                            } catch (_: Throwable) {}
                        },
                        onStatus = { state, error ->
                            runOnUiThread {
                                try {
                                    when (state) {
                                        "active" -> status.text = "🔴 Jonli ovoz"
                                        "failed" -> fail(error ?: "Ovoz ulanmadi")
                                        "stopped" -> if (!stopping) resetUi("To'xtatildi")
                                    }
                                } catch (t: Throwable) { fail(t.message) }
                            }
                        }
                    )
                } catch (t: Throwable) { fail(t.message) }
            }
        }
    }

    private fun fail(message: String?) {
        val text = "❌ ${message ?: "Ulanmadi"}"
        resetUi(text)
    }

    private fun stopListening() {
        stopping = true
        status.text = "⏳ To'xtatilmoqda..."
        stopButton.isEnabled = false
        AmbientAudioRepository.requestStop()
        resetUi("To'xtatildi")
        stopping = false
    }

    private fun resetUi(message: String = "Tayyor") {
        sessionListener?.remove()
        sessionListener = null
        try { peerConnection?.close() } catch (_: Throwable) {}
        try { peerConnection?.dispose() } catch (_: Throwable) {}
        try { audioDeviceModule?.release() } catch (_: Throwable) {}
        try { factory?.dispose() } catch (_: Throwable) {}
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = false
        } catch (_: Throwable) {}
        peerConnection = null
        audioDeviceModule = null
        factory = null
        currentRequestId = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        status.text = message
    }

    override fun onDestroy() {
        if (currentRequestId != null && !stopping) AmbientAudioRepository.requestStop()
        requestListener?.remove()
        sessionListener?.remove()
        try { peerConnection?.close() } catch (_: Throwable) {}
        try { peerConnection?.dispose() } catch (_: Throwable) {}
        try { audioDeviceModule?.release() } catch (_: Throwable) {}
        try { factory?.dispose() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
