package uz.oilanazorati.parentcontrol.ui

import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.repo.AmbientAudioRepository

/** Ota-ona tomonidagi jonli ovoz — Firestore PCM16 transport. */
class AmbientListenActivity : AppCompatActivity() {
    private var requestListener: ListenerRegistration? = null
    private var audioListener: ListenerRegistration? = null
    private var audioTrack: AudioTrack? = null
    private var currentRequestId: String? = null
    private var currentSessionId: String? = null
    private var stopping = false
    private val seenSequences = HashSet<Int>()

    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_bg))
        }
        val title = TextView(this).apply {
            text = "🎙️ Ovoz"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
        }
        status = TextView(this).apply {
            text = "Tayyor"
            textSize = 14f
            setPadding(0, 18, 0, 18)
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
        }
        startButton = Button(this).apply {
            text = "Eshitishni boshlash"
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            setBackgroundResource(uz.oilanazorati.parentcontrol.R.drawable.bg_card_theme)
            val icon = androidx.core.content.ContextCompat.getDrawable(this@AmbientListenActivity, uz.oilanazorati.parentcontrol.R.drawable.ic_play)?.mutate()
            icon?.setColorFilter(currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = 16
        }
        stopButton = Button(this).apply {
            text = "To'xtatish"
            isEnabled = false
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            setBackgroundResource(uz.oilanazorati.parentcontrol.R.drawable.bg_card_theme)
            val icon = androidx.core.content.ContextCompat.getDrawable(this@AmbientListenActivity, uz.oilanazorati.parentcontrol.R.drawable.ic_stop)?.mutate()
            icon?.setColorFilter(currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = 16
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * resources.displayMetrics.density).toInt() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(startButton)
        root.addView(stopButton)
        setContentView(root)

        startButton.setOnClickListener { startListening() }
        stopButton.setOnClickListener { stopListening() }
        requestListener = AmbientAudioRepository.listenRequestStatus { _, state, sessionId ->
            runOnUiThread {
                if (currentRequestId == null) status.text = statusLabel(state)
                if (currentRequestId != null && state == "active" && !sessionId.isNullOrBlank() && currentSessionId == null) {
                    currentSessionId = sessionId
                    startPlayback(sessionId)
                }
                if (currentRequestId != null && state == "failed") {
                    status.text = "❌ Mikrofonni ulab bo'lmadi"
                    resetUi()
                }
                if (currentRequestId != null && state == "stopped") resetUi("To'xtatildi")
            }
        }
    }

    private fun statusLabel(state: String?): String = when (state) {
        "requested" -> "⏳ Bola qurilmasidan kutilmoqda..."
        "active" -> "🔴 Jonli ovoz"
        "stopped" -> "To'xtatildi"
        "failed" -> "❌ Mikrofonni ulab bo'lmadi"
        else -> "Tayyor"
    }

    private fun startListening() {
        if (currentRequestId != null) return
        stopping = false
        seenSequences.clear()
        status.text = "⏳ So'rov yuborilmoqda..."
        startButton.isEnabled = false
        stopButton.isEnabled = true
        currentRequestId = "pending"
        AmbientAudioRepository.requestStart { ok, error ->
            runOnUiThread {
                if (!ok) {
                    status.text = "❌ ${error ?: "So'rov yuborilmadi"}"
                    resetUi()
                } else {
                    status.text = "⏳ Bola qurilmasidan kutilmoqda..."
                }
            }
        }
    }

    private fun startPlayback(sessionId: String) {
        try {
            audioListener?.remove()
            audioTrack?.release()
            val minBuffer = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IllegalStateException("AudioTrack buffer xatosi")
            val bufferSize = maxOf(minBuffer, 16000 * 2)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            status.text = "🔴 Jonli ovoz"
            audioListener = AmbientAudioRepository.listenAudioChunks(sessionId) { sequence, bytes ->
                if (!seenSequences.add(sequence)) return@listenAudioChunks
                try { audioTrack?.write(bytes, 0, bytes.size) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            status.text = "❌ Ovoz chiqarishda xato"
            resetUi()
        }
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
        audioListener?.remove()
        audioListener = null
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        currentRequestId = null
        currentSessionId = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        status.text = message
    }

    override fun onDestroy() {
        if (currentRequestId != null && !stopping) AmbientAudioRepository.requestStop()
        requestListener?.remove()
        audioListener?.remove()
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        super.onDestroy()
    }
}
