package uz.oilanazorati.parentcontrol.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.service.AgoraConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Android parent viewer for an explicitly child-approved iOS ReplayKit broadcast. */
class IOSScreenShareViewerActivity : AppCompatActivity() {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var requestListener: ListenerRegistration? = null
    private var engine: RtcEngine? = null
    private var requestId = ""
    private var channelName = ""
    private lateinit var status: TextView
    private lateinit var video: SurfaceView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var childUid = ""
    private var familyCode = ""

    private val handler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            runOnUiThread { status.text = "Kanal ulandi. Bola ekran uzatishini kuting…" }
        }
        override fun onUserJoined(uid: Int, elapsed: Int) {
            runOnUiThread {
                video.visibility = android.view.View.VISIBLE
                engine?.setupRemoteVideo(VideoCanvas(video, VideoCanvas.RENDER_MODE_FIT, uid))
                status.text = "🔴 Farzand ekrani jonli uzatilmoqda"
            }
        }
        override fun onUserOffline(uid: Int, reason: Int) {
            runOnUiThread { status.text = "Bola ekran uzatishni to‘xtatdi" }
        }
        override fun onError(err: Int) {
            runOnUiThread { status.text = "Agora ekran xatosi: $err"; releaseEngine() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        familyCode = FirebaseRepo.familyCode.orEmpty()
        childUid = FirebaseRepo.childId.orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        root.addView(TextView(this).apply {
            text = "Jonli ekran"
            textSize = 24f
            setTextColor(Color.rgb(25, 35, 50))
            setPadding(0, 0, 0, 12)
        })
        status = TextView(this).apply { text = "Ota-ona so‘rovi tayyor"; textSize = 15f; setPadding(0, 8, 0, 16) }
        root.addView(status)
        start = Button(this).apply {
            text = "Ekranni ko‘rish so‘rovini yuborish"
            setOnClickListener { requestScreenShare() }
        }
        root.addView(start)
        stop = Button(this).apply {
            text = "Uzatishni to‘xtatish"
            isEnabled = false
            setOnClickListener { stopRemote() }
        }
        root.addView(stop)
        video = RtcEngine.CreateRendererView(this).apply {
            setZOrderMediaOverlay(true)
            visibility = android.view.View.GONE
        }
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 16 })
        setContentView(root)
        if (familyCode.isBlank() || childUid.isBlank()) {
            status.text = "Avval farzand qurilmasini tanlang"; start.isEnabled = false; return
        }
        FirebaseRepo.checkIsPremium { premium ->
            runOnUiThread {
                if (!premium) {
                    status.text = "Jonli ekran Premium bilan ishlaydi"
                    start.isEnabled = false
                }
            }
        }
    }

    private fun requestScreenShare() {
        val ownerUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            status.text = "Ota-ona akkaunti aniqlanmadi"; return
        }
        requestId = UUID.randomUUID().toString()
        channelName = "scr_" + UUID.randomUUID().toString().replace("-", "").lowercase()
        status.text = "Bola qurilmasiga so‘rov yuborilmoqda…"
        start.isEnabled = false
        db.collection("families").document(familyCode).collection("children").document(childUid)
            .collection("screen_share_requests").document("current")
            .set(mapOf(
                "requestId" to requestId, "channelName" to channelName, "status" to "requested",
                "requestedByUid" to ownerUid, "platform" to "ios",
                "createdAtMs" to System.currentTimeMillis(), "updatedAtMs" to System.currentTimeMillis(),
                "expiresAtMs" to System.currentTimeMillis() + 10 * 60 * 1000
            )).addOnSuccessListener {
                stop.isEnabled = true
                listenRequest()
            }.addOnFailureListener { error ->
                status.text = "So‘rov yuborilmadi: ${error.localizedMessage}"
                start.isEnabled = true
            }
    }

    private fun listenRequest() {
        requestListener?.remove()
        requestListener = db.collection("families").document(familyCode).collection("children").document(childUid)
            .collection("screen_share_requests").document("current")
            .addSnapshotListener { snapshot, error ->
                if (error != null) { runOnUiThread { status.text = error.localizedMessage }; return }
                val data = snapshot?.data ?: return
                if (data["requestId"] != requestId) return
                when (data["status"] as? String) {
                    "requested" -> runOnUiThread { status.text = "iPhone’da bola so‘rovni tasdiqlashi kerak" }
                    "broadcast_ready", "active" -> {
                        if (engine == null) getSubscriberTokenAndJoin()
                    }
                    "stop_requested", "stopped", "failed", "expired" -> runOnUiThread {
                        releaseEngine(); status.text = "Ekran sessiyasi tugadi"; start.isEnabled = true; stop.isEnabled = false
                    }
                }
            }
    }

    private fun getSubscriberTokenAndJoin() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(false).addOnSuccessListener { authToken ->
            Thread {
                try {
                    val connection = URL("https://us-central1-oilanazorat-3c8de.cloudfunctions.net/agoraScreenToken")
                        .openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer ${authToken.token}")
                    val uid = (1000..Int.MAX_VALUE).random()
                    val body = JSONObject().apply {
                        put("familyCode", familyCode); put("childUid", childUid)
                        put("requestId", requestId); put("channelName", channelName)
                        put("uid", uid); put("role", "subscriber")
                    }
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    if (connection.responseCode !in 200..299) throw IllegalStateException("Token server: ${connection.responseCode}")
                    val response = JSONObject(connection.inputStream.bufferedReader().readText())
                    val appId = response.getString("appId")
                    val token = response.getString("token")
                    runOnUiThread { joinAgora(appId, token, uid) }
                    connection.disconnect()
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Token/ulanish xatosi: ${e.localizedMessage}"; start.isEnabled = true; stop.isEnabled = false }
                }
            }.start()
        }.addOnFailureListener { status.text = it.localizedMessage }
    }

    private fun joinAgora(appId: String, token: String, uid: Int) {
        if (engine != null) return
        val config = RtcEngineConfig().apply { mContext = applicationContext; mAppId = appId; mEventHandler = handler }
        engine = try { RtcEngine.create(config) } catch (e: Exception) {
            status.text = "Agora ishga tushmadi: ${e.localizedMessage}"; null
        } ?: return
        engine?.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
        engine?.enableVideo()
        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            publishMicrophoneTrack = false
            publishCameraTrack = false
            publishScreenCaptureVideo = false
            autoSubscribeAudio = false
            autoSubscribeVideo = true
        }
        val result = engine?.joinChannel(token, channelName, uid, options) ?: -1
        if (result != Constants.ERR_OK) {
            status.text = "Agora join xatosi: $result"; releaseEngine()
        } else status.text = "iPhone Broadcast videosi kutilmoqda…"
    }

    private fun stopRemote() {
        db.collection("families").document(familyCode).collection("children").document(childUid)
            .collection("screen_share_requests").document("current")
            .update(mapOf("status" to "stop_requested", "updatedAtMs" to System.currentTimeMillis()))
        releaseEngine()
        status.text = "To‘xtatish so‘rovi yuborildi"
        start.isEnabled = true; stop.isEnabled = false
    }

    private fun releaseEngine() {
        try { engine?.leaveChannel() } catch (_: Exception) {}
        try { engine?.disableVideo() } catch (_: Exception) {}
        try { RtcEngine.destroy() } catch (_: Exception) {}
        engine = null
        video.visibility = android.view.View.GONE
    }

    override fun onDestroy() {
        requestListener?.remove()
        releaseEngine()
        super.onDestroy()
    }
}
