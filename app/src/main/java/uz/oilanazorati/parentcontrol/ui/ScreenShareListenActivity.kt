package uz.oilanazorati.parentcontrol.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/** Android parent receiver for an iOS child's ReplayKit screen stream. */
class ScreenShareListenActivity : AppCompatActivity() {
    private val db = FirebaseFirestore.getInstance()
    private var engine: RtcEngine? = null
    private var requestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var requestId = ""
    private var channel = ""
    private var joined = false
    private lateinit var status: TextView
    private lateinit var videoContainer: FrameLayout
    private var requestRef: com.google.firebase.firestore.DocumentReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            text = "iPhone ekran ulanishi kutilmoqda…"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(24, 20, 24, 20)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoContainer, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        setContentView(root)
        val code = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode
        val childId = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.childId
        val owner = FirebaseAuth.getInstance().currentUser?.uid
        if (code.isNullOrBlank() || childId.isNullOrBlank() || owner.isNullOrBlank()) {
            toast("Avval oila va farzandni tanlang"); finish(); return
        }
        db.collection("parents").document(owner).get().addOnSuccessListener { parent ->
            if (parent.getBoolean("premium") != true) {
                toast("Jonli ekran uchun Premium kerak"); finish(); return@addOnSuccessListener
            }
            db.collection("families").document(code).collection("children").document(childId).get()
                .addOnSuccessListener { child ->
                    val platform = child.getString("childPlatform") ?: child.getString("platform")
                    if (platform != "ios") {
                        toast("Jonli ekran uzatish hozir iOS bola qurilmasida qo‘llanadi")
                        finish(); return@addOnSuccessListener
                    }
                    startRequest(code, childId, owner)
                }
        }
    }

    private fun startRequest(code: String, childId: String, owner: String) {
        requestId = "scr_${UUID.randomUUID()}"
        channel = "sc_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        requestRef = db.collection("families").document(code).collection("children")
            .document(childId).collection("screen_requests").document("current")
        requestRef!!.set(mapOf(
            "requestId" to requestId, "channelName" to channel, "requestedByUid" to owner,
            "status" to "requested", "transport" to "agora", "platform" to "ios",
            "requestedAtMs" to System.currentTimeMillis(), "expiresAtMs" to System.currentTimeMillis() + 180_000
        )).addOnFailureListener {
            toast("Ekran so‘rovi yuborilmadi: ${it.localizedMessage}"); finish()
        }
        requestListener = requestRef!!.addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            when (snap.getString("status")) {
                "active" -> if (!joined) getTokenAndJoin(code, childId)
                "stop_requested", "stopped", "failed", "expired", "cancelled" -> {
                    status.text = "Ekran uzatish to‘xtadi"; leaveAgora()
                    if (snap.getString("status") == "stop_requested") requestRef?.update("status", "stopped")
                }
                else -> status.text = "iPhone’da bola «Ekran uzatishni boshlash»ni bosing"
            }
        }
    }

    private fun getTokenAndJoin(code: String, childId: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnSuccessListener { auth ->
            thread {
                try {
                    val connection = URL("https://us-central1-oilanazorat-3c8de.cloudfunctions.net/agoraVoiceToken").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Authorization", "Bearer ${auth.token}")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.outputStream.use {
                        it.write(JSONObject().put("purpose", "screen").put("familyCode", code)
                            .put("childUid", childId).put("requestId", requestId)
                            .put("channelName", channel).put("uid", 1)
                            .put("role", "screen_subscriber").toString().toByteArray())
                    }
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val token = json.getString("token")
                    val appId = json.getString("appId")
                    runOnUiThread { joinChannel(appId, token) }
                    connection.disconnect()
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Token xatosi: ${e.localizedMessage}" }
                }
            }
        }.addOnFailureListener { status.text = "Hisob tokeni olinmadi" }
    }

    private fun joinChannel(appId: String, token: String) {
        if (engine != null || isFinishing) return
        try {
            val config = RtcEngineConfig().apply {
                mContext = applicationContext; mAppId = appId
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                mEventHandler = object : IRtcEngineEventHandler() {
                    override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                        runOnUiThread { status.text = "iPhone ekraniga ulanildi…"; joined = true }
                    }
                    override fun onUserJoined(uid: Int, elapsed: Int) {
                        runOnUiThread {
                            val surface = SurfaceView(this@ScreenShareListenActivity)
                            videoContainer.removeAllViews()
                            videoContainer.addView(surface, FrameLayout.LayoutParams(-1, -1))
                            engine?.setupRemoteVideo(io.agora.rtc2.video.VideoCanvas(surface, Constants.RENDER_MODE_HIDDEN, uid))
                            status.text = "Farzand ekrani jonli"
                        }
                    }
                    override fun onUserOffline(uid: Int, reason: Int) {
                        runOnUiThread { status.text = "Ekran uzatish tugadi" }
                    }
                    override fun onError(err: Int) { runOnUiThread { status.text = "Agora xatosi: $err" } }
                }
            }
            val rtc = RtcEngine.create(config)
            engine = rtc
            rtc.enableVideo()
            rtc.setClientRole(Constants.CLIENT_ROLE_AUDIENCE)
            val options = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
                publishMicrophoneTrack = false; publishCameraTrack = false
                autoSubscribeAudio = false; autoSubscribeVideo = true
            }
            val result = rtc.joinChannel(token, channel, 1, options)
            if (result != 0) { status.text = "Kanalga ulanish xatosi: $result"; leaveAgora() }
        } catch (e: Exception) { status.text = "Agora ishga tushmadi: ${e.localizedMessage}" }
    }

    private fun leaveAgora() {
        joined = false
        engine?.leaveChannel()
        engine?.let { RtcEngine.destroy() }
        engine = null
        videoContainer.removeAllViews()
    }

    override fun onDestroy() {
        requestListener?.remove()
        if (isFinishing) requestRef?.update("status", "stop_requested")
        leaveAgora()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}