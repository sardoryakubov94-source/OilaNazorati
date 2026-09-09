package uz.oilanazorati.parentcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.util.concurrent.atomic.AtomicBoolean

/** Real-time microphone transport. The service is started on-demand by the monitor service. */
class WebRtcAmbientAudioService : Service() {
    private val db = FirebaseFirestore.getInstance()
    private var requestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private var requestId: String? = null
    private var lastOffer: String? = null
    private val appliedParentCandidates = HashSet<String>()

    companion object {
        const val CHANNEL_ID = "oila_nazorati_mic_webrtc"
        const val NOTIFICATION_ID = 511
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, idleNotification(), foregroundTypes())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (requestListener == null) listenForRequests()
        return START_NOT_STICKY
    }

    private fun foregroundTypes(): Int = if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    private fun listenForRequests() {
        val family = FirebaseRepo.familyCode ?: run { stopSelf(); return }
        val child = FirebaseRepo.childId ?: FirebaseAuth.getInstance().currentUser?.uid ?: run { stopSelf(); return }
        val ref = db.collection("families").document(family).collection("children").document(child).collection("mic_requests").document("current")
        requestListener = ref.addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            val data = snap.data.orEmpty()
            val id = data["requestId"] as? String ?: return@addSnapshotListener
            if (data["transport"] != "webrtc") return@addSnapshotListener
            requestId = id
            when (data["status"] as? String) {
                "webrtc_requested", "requested" -> {
                    val offer = data["webrtcOffer"] as? String
                    if (!offer.isNullOrBlank() && offer != lastOffer) startSession(id, offer, ref)
                }
                "stop_requested", "webrtc_stop_requested", "stopped", "failed" -> if (running.get()) stopSession(data["status"] as String, ref)
            }
            val candidates = data["parentCandidates"] as? List<*> ?: emptyList<Any>()
            candidates.forEach { raw ->
                val m = raw as? Map<*, *> ?: return@forEach
                val candidate = m["candidate"] as? String ?: return@forEach
                val mid = m["sdpMid"] as? String
                val index = (m["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                if (appliedParentCandidates.add(candidate)) peerConnection?.addIceCandidate(IceCandidate(mid, index, candidate))
            }
        }
    }

    private fun startSession(id: String, offer: String, requestRef: com.google.firebase.firestore.DocumentReference) {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateRequest(requestRef, id, "failed", "Mikrofon ruxsati berilmagan")
            stopSelf()
            return
        }
        stopPeerOnly()
        lastOffer = offer
        running.set(true)
        appliedParentCandidates.clear()
        updateActiveNotification()
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
            audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false).createAudioDeviceModule()
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDeviceModule).createPeerConnectionFactory()
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
            peerConnection = factory?.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate) {
                    try { requestRef.update("childCandidates", FieldValue.arrayUnion(mapOf("candidate" to candidate.sdp, "sdpMid" to candidate.sdpMid, "sdpMLineIndex" to candidate.sdpMLineIndex))) }
                    catch (t: Throwable) { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(t) }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {}
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
                override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
            })
            if (peerConnection == null) throw IllegalStateException("WebRTC PeerConnection yaratilmadi")
            audioSource = factory!!.createAudioSource(MediaConstraints())
            audioTrack = factory!!.createAudioTrack("oila-mic", audioSource)
            audioTrack!!.setEnabled(true)
            peerConnection!!.addTrack(audioTrack!!)
            peerConnection!!.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onSetSuccess() { createAnswer(requestRef, id) }
                override fun onCreateFailure(error: String?) { fail(requestRef, id, error) }
                override fun onSetFailure(error: String?) { fail(requestRef, id, error) }
            }, SessionDescription(SessionDescription.Type.OFFER, offer))
        } catch (t: Throwable) {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply { setCustomKey("webrtc_child_phase", "startSession"); recordException(t) }
            fail(requestRef, id, t.message ?: "WebRTC ishga tushmadi")
        }
    }

    private fun createAnswer(requestRef: com.google.firebase.firestore.DocumentReference, id: String) {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                try {
                    if (desc == null) return fail(requestRef, id, "WebRTC answer bo'sh")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(d: SessionDescription?) {}
                        override fun onSetSuccess() {
                            try { requestRef.update("webrtcAnswer", desc.description, "updatedAt", System.currentTimeMillis()) }
                            catch (t: Throwable) { fail(requestRef, id, t.message) }
                        }
                        override fun onCreateFailure(error: String?) { fail(requestRef, id, error) }
                        override fun onSetFailure(error: String?) { fail(requestRef, id, error) }
                    }, desc)
                } catch (t: Throwable) { fail(requestRef, id, t.message) }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { fail(requestRef, id, error) }
            override fun onSetFailure(error: String?) { fail(requestRef, id, error) }
        }, MediaConstraints())
    }

    private fun fail(ref: com.google.firebase.firestore.DocumentReference, id: String, error: String?) {
        if (running.get()) updateRequest(ref, id, "failed", error ?: "WebRTC xatosi")
        running.set(false)
        stopPeerOnly()
        requestListener?.remove(); requestListener = null
        restoreIdleNotification()
        stopSelf()
    }

    private fun stopSession(status: String, ref: com.google.firebase.firestore.DocumentReference) {
        running.set(false)
        stopPeerOnly()
        requestListener?.remove(); requestListener = null
        restoreIdleNotification()
        stopSelf()
    }

    private fun stopPeerOnly() {
        try { audioTrack?.setEnabled(false) } catch (_: Throwable) {}
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        try { peerConnection?.close() } catch (_: Throwable) {}
        try { peerConnection?.dispose() } catch (_: Throwable) {}
        try { factory?.dispose() } catch (_: Throwable) {}
        try { audioDeviceModule?.release() } catch (_: Throwable) {}
        audioTrack = null; audioSource = null; peerConnection = null; factory = null; audioDeviceModule = null
    }

    private fun updateRequest(ref: com.google.firebase.firestore.DocumentReference, id: String, status: String, error: String? = null) {
        val data = mutableMapOf<String, Any>("requestId" to id, "status" to status, "updatedAt" to System.currentTimeMillis())
        if (error != null) data["error"] = error.take(200)
        ref.update(data)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ovoz nazorati", NotificationManager.IMPORTANCE_LOW))
    }

    private fun idleNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_blank).setContentTitle("Oila Nazorati").setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).setShowWhen(false).build()
    private fun activeNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_blank).setContentTitle("🎙️ Mikrofon faol").setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).setShowWhen(false).build()
    private fun updateActiveNotification() { if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, activeNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIFICATION_ID, activeNotification()) }
    private fun restoreIdleNotification() { if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, idleNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIFICATION_ID, idleNotification()) }

    override fun onDestroy() { requestListener?.remove(); running.set(false); stopPeerOnly(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
