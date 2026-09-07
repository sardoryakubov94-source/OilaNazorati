package uz.oilanazorati.parentcontrol.ui

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import uz.oilanazorati.parentcontrol.repo.AmbientAudioRepository

/**
 * Ota-ona tomonidagi jonli ovoz eshitish ekrani — WebRTC orqali.
 */
class AmbientListenActivity : AppCompatActivity() {
    private var requestListener: ListenerRegistration? = null
    private var sessionListener: ListenerRegistration? = null
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var currentRequestId: String? = null
    private var remoteDescriptionSet = false
    private val appliedChildCandidates = HashSet<String>()
    private var offerDocReady = false
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()

    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28); setBackgroundColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_bg)) }
        val title = TextView(this).apply { text = "🎙️ Ovoz"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary)) }
        status = TextView(this).apply { text = "Tayyor"; textSize = 14f; setPadding(0, 18, 0, 18); setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary)) }
        startButton = Button(this).apply {
            text = "Eshitishni boshlash"; setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary)); setBackgroundResource(uz.oilanazorati.parentcontrol.R.drawable.bg_card_theme)
            val icon = androidx.core.content.ContextCompat.getDrawable(this@AmbientListenActivity, uz.oilanazorati.parentcontrol.R.drawable.ic_play)?.mutate(); icon?.setColorFilter(currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN); setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null); compoundDrawablePadding = 16
        }
        stopButton = Button(this).apply {
            text = "To'xtatish"; isEnabled = false; setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary)); setBackgroundResource(uz.oilanazorati.parentcontrol.R.drawable.bg_card_theme)
            val icon = androidx.core.content.ContextCompat.getDrawable(this@AmbientListenActivity, uz.oilanazorati.parentcontrol.R.drawable.ic_stop)?.mutate(); icon?.setColorFilter(currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN); setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null); compoundDrawablePadding = 16
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * resources.displayMetrics.density).toInt() }
        }
        root.addView(title); root.addView(status); root.addView(startButton); root.addView(stopButton); setContentView(root)
        startButton.setOnClickListener { startListening() }; stopButton.setOnClickListener { stopListening() }
        requestListener = AmbientAudioRepository.listenRequestStatus { _, state, _ -> if (currentRequestId == null) runOnUiThread { status.text = statusLabel(state) } }
    }

    private fun statusLabel(state: String?): String = when (state) { "requested" -> "⏳ Bola qurilmasidan kutilmoqda..."; "active" -> "🔴 Jonli ovoz"; "stopped" -> "To'xtatildi"; "failed" -> "❌ Mikrofonni ulab bo'lmadi"; else -> "Tayyor" }

    private fun startListening() {
        cleanupPeer(); status.text = "⏳ Ulanmoqda..."; startButton.isEnabled = false; remoteDescriptionSet = false; appliedChildCandidates.clear(); offerDocReady = false; pendingLocalCandidates.clear()
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
            audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext).createAudioDeviceModule()
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDeviceModule).createPeerConnectionFactory()
            val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(), PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
            peerConnection = factory?.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate) { synchronized(pendingLocalCandidates) { if (offerDocReady) AmbientAudioRepository.sendParentIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex) else pendingLocalCandidates.add(candidate) } }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {}
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) { runOnUiThread { when (newState) { PeerConnection.PeerConnectionState.CONNECTED -> { status.text = "🔴 Jonli ovoz"; stopButton.isEnabled = true }; PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.CLOSED -> if (currentRequestId != null) failListening("Ulanish uzildi"); else -> {} } } }
                override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                override fun onTrack(transceiver: RtpTransceiver?) { (transceiver?.receiver?.track() as? org.webrtc.AudioTrack)?.setEnabled(true) }
            })
            val pc = peerConnection ?: throw IllegalStateException("PeerConnection yaratilmadi")
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) { if (desc == null) return runOnUiThread { failListening("SDP taklif bo'sh") }; pc.setLocalDescription(object : SdpObserver { override fun onCreateSuccess(d: SessionDescription?) {}; override fun onSetSuccess() { sendOffer(desc.description) }; override fun onCreateFailure(error: String?) { runOnUiThread { failListening(error) } }; override fun onSetFailure(error: String?) { runOnUiThread { failListening(error) } } }, desc) }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { runOnUiThread { failListening(error) } }
                override fun onSetFailure(error: String?) { runOnUiThread { failListening(error) } }
            }, MediaConstraints())
        } catch (t: Throwable) { failListening(t.message ?: "WebRTC ishga tushmadi") }
    }

    private fun sendOffer(offerSdp: String) {
        AmbientAudioRepository.requestStartWebRtc(offerSdp) { ok, error, requestId -> runOnUiThread {
            if (!ok || requestId == null) { failListening(error); return@runOnUiThread }
            currentRequestId = requestId; status.text = "⏳ Bola qurilmasidan kutilmoqda..."
            synchronized(pendingLocalCandidates) { offerDocReady = true; pendingLocalCandidates.forEach { AmbientAudioRepository.sendParentIceCandidate(it.sdp, it.sdpMid, it.sdpMLineIndex) }; pendingLocalCandidates.clear() }
            sessionListener = AmbientAudioRepository.listenWebRtcSession(requestId, onAnswer = { answerSdp -> if (!remoteDescriptionSet) { remoteDescriptionSet = true; peerConnection?.setRemoteDescription(object : SdpObserver { override fun onCreateSuccess(d: SessionDescription?) {}; override fun onSetSuccess() {}; override fun onCreateFailure(error: String?) { runOnUiThread { failListening(error) } }; override fun onSetFailure(error: String?) { runOnUiThread { failListening(error) } } }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp)) } }, onChildCandidate = { candidate, mid, index -> if (appliedChildCandidates.add(candidate)) peerConnection?.addIceCandidate(IceCandidate(mid, index, candidate)) }, onStatus = { newStatus, statusError -> runOnUiThread { when (newStatus) { "failed" -> failListening(statusError ?: "Mikrofonni ulab bo'lmadi"); "stopped" -> resetToIdle("To'xtatildi") } } })
        } }
    }

    private fun failListening(error: String?) { status.text = "❌ ${error ?: "Xato"}"; resetToIdle(null, keepMessage = true) }
    private fun resetToIdle(message: String?, keepMessage: Boolean = false) { if (!keepMessage) status.text = message ?: "Tayyor"; startButton.isEnabled = true; stopButton.isEnabled = false; currentRequestId = null; cleanupPeer() }
    private fun stopListening() { status.text = "⏳ To'xtatilmoqda..."; stopButton.isEnabled = false; AmbientAudioRepository.requestStop(); resetToIdle("To'xtatildi") }
    private fun cleanupPeer() { sessionListener?.remove(); sessionListener = null; try { peerConnection?.close() } catch (_: Throwable) {}; try { peerConnection?.dispose() } catch (_: Throwable) {}; try { factory?.dispose() } catch (_: Throwable) {}; try { audioDeviceModule?.release() } catch (_: Throwable) {}; peerConnection = null; factory = null; audioDeviceModule = null }
    override fun onDestroy() { if (currentRequestId != null) AmbientAudioRepository.requestStop(); requestListener?.remove(); requestListener = null; cleanupPeer(); super.onDestroy() }
}
