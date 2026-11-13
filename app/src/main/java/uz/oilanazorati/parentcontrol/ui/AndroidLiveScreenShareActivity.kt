package uz.oilanazorati.parentcontrol.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import uz.oilanazorati.parentcontrol.service.AndroidLiveScreenShareService

/**
 * Visible child-side consent screen. The child must tap Allow and accept
 * Android's own MediaProjection dialog before any screen pixels are sent.
 */
class AndroidLiveScreenShareActivity : AppCompatActivity() {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var familyCode = ""
    private var childUid = ""
    private var requestId = ""
    private var channelName = ""
    private lateinit var statusView: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val service = Intent(this, AndroidLiveScreenShareService::class.java).apply {
                putExtra(AndroidLiveScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AndroidLiveScreenShareService.EXTRA_RESULT_DATA, data)
                putExtra(AndroidLiveScreenShareService.EXTRA_FAMILY_CODE, familyCode)
                putExtra(AndroidLiveScreenShareService.EXTRA_CHILD_UID, childUid)
                putExtra(AndroidLiveScreenShareService.EXTRA_REQUEST_ID, requestId)
                putExtra(AndroidLiveScreenShareService.EXTRA_CHANNEL, channelName)
            }
            try {
                ContextCompat.startForegroundService(this, service)
                statusView.text = "Jonli ekran uzatish ishga tushirilmoqda. Xizmat bildirishnomasi ko‘rinib turadi."
                finish()
            } catch (error: Exception) {
                updateRequest("failed", error.localizedMessage ?: "Ekran xizmati ishga tushmadi")
                statusView.text = "Uzatish boshlanmadi: ${error.localizedMessage}"
            }
        } else {
            updateRequest("cancelled", "Bola Android ekran ulashish ruxsatini bermadi")
            statusView.text = "Ekran ulashish bekor qilindi."
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        familyCode = intent.getStringExtra(EXTRA_FAMILY_CODE).orEmpty()
        childUid = intent.getStringExtra(EXTRA_CHILD_UID).orEmpty()
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        channelName = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()

        statusView = TextView(this).apply {
            textSize = 16f
            text = "Ota-ona jonli ekran ko‘rish so‘rovini yubordi."
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(this@AndroidLiveScreenShareActivity).apply {
                text = "Jonli ekran ulashish"
                textSize = 22f
                setPadding(0, 0, 0, 20)
            })
            addView(statusView)
            addView(TextView(this@AndroidLiveScreenShareActivity).apply {
                text = "Ruxsat bersangiz, telefoningiz ekrani ota-onangizning iPhone qurilmasiga jonli uzatiladi. Android tizimi ulashish haqida doimiy ko‘rsatkich/bildirishnoma beradi. Istalgan payt to‘xtatishingiz mumkin."
                textSize = 14f
                setPadding(0, 18, 0, 24)
            })
            addView(Button(this@AndroidLiveScreenShareActivity).apply {
                text = "Davom etish va Android ruxsatini berish"
                setOnClickListener { requestSystemConsent() }
            })
            addView(Button(this@AndroidLiveScreenShareActivity).apply {
                text = "Rad etish"
                setOnClickListener {
                    updateRequest("cancelled", "Bola ekran uzatishni rad etdi")
                    finish()
                }
            })
        }
        setContentView(root)

        if (familyCode.isBlank() || childUid.isBlank() || requestId.isBlank() || channelName.isBlank()) {
            statusView.text = "So‘rov ma’lumotlari noto‘g‘ri."
            finish()
            return
        }
        val requestRef = db.collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_requests").document("current")
        requestRef.get().addOnSuccessListener { snapshot ->
            val valid = snapshot.exists() &&
                snapshot.getString("requestId") == requestId &&
                snapshot.getString("status") == "requested" &&
                ((snapshot.getLong("expiresAtMs") ?: 0L) == 0L ||
                    (snapshot.getLong("expiresAtMs") ?: 0L) > System.currentTimeMillis())
            if (!valid) {
                statusView.text = "Bu ekran so‘rovi eskirgan yoki bekor qilingan."
                finish()
            }
        }.addOnFailureListener {
            statusView.text = "So‘rovni tekshirib bo‘lmadi: ${it.localizedMessage}"
        }
    }

    private fun requestSystemConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun updateRequest(state: String, message: String) {
        if (familyCode.isBlank() || childUid.isBlank()) return
        db.collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_requests").document("current")
            .update(
                mapOf(
                    "status" to state,
                    "message" to message,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
    }

    companion object {
        const val EXTRA_FAMILY_CODE = "screen_family_code"
        const val EXTRA_CHILD_UID = "screen_child_uid"
        const val EXTRA_REQUEST_ID = "screen_request_id"
        const val EXTRA_CHANNEL = "screen_channel"
    }
}
