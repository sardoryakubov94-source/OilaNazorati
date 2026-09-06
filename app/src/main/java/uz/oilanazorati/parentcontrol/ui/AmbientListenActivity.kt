package uz.oilanazorati.parentcontrol.ui

import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.repo.AmbientAudioRepository

class AmbientListenActivity : AppCompatActivity() {
    private var requestListener: ListenerRegistration? = null
    private var audioListener: ListenerRegistration? = null
    private var audioTrack: AudioTrack? = null
    private var currentSessionId: String? = null
    private val seen = HashSet<Int>()
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ovoz oynasi ham ilovaning umumiy mavzusidagi fon va matn ranglaridan foydalanadi.
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
            text = "▶️ Eshitishni boshlash"
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            setBackgroundResource(uz.oilanazorati.parentcontrol.R.drawable.bg_card_theme)
        }
        stopButton = Button(this).apply {
            text = "⏹️ To'xtatish"
            isEnabled = false
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            setBackgroundResource(uz.oilanazorati.parentcontrol.R.drawable.bg_card_theme)
        }
        root.addView(title)
        root.addView(status)
        root.addView(startButton)
        root.addView(stopButton)
        setContentView(root)

        startButton.setOnClickListener { startListening() }
        stopButton.setOnClickListener { stopListening() }
        requestListener = AmbientAudioRepository.listenRequestStatus { requestId, state, sessionId ->
            runOnUiThread { handleStatus(state, sessionId) }
        }
    }

    private fun startListening() {
        seen.clear()
        status.text = "⏳ So'rov yuborilmoqda..."
        startButton.isEnabled = false
        AmbientAudioRepository.requestStart { ok, error ->
            runOnUiThread {
                if (!ok) {
                    status.text = "❌ ${error ?: "Xato"}"
                    startButton.isEnabled = true
                }
            }
        }
    }

    private fun handleStatus(state: String?, sessionId: String?) {
        when (state) {
            "requested" -> status.text = "⏳ Bola qurilmasidan kutilmoqda..."
            "active" -> {
                status.text = "🔴 Jonli ovoz"
                startButton.isEnabled = false
                stopButton.isEnabled = true
                if (sessionId != null && sessionId != currentSessionId) {
                    currentSessionId = sessionId
                    attachAudio(sessionId)
                }
            }
            "stopped" -> {
                status.text = "To'xtatildi"
                startButton.isEnabled = true
                stopButton.isEnabled = false
                detachAudio()
            }
            "failed" -> {
                status.text = "❌ Mikrofonni ulab bo'lmadi"
                startButton.isEnabled = true
                stopButton.isEnabled = false
                detachAudio()
            }
        }
    }

    private fun attachAudio(sessionId: String) {
        detachAudio()
        val min = AudioTrack.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) {
            status.text = "❌ Audio chiqishi mavjud emas"
            return
        }
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(16000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, 16000 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
        audioListener = AmbientAudioRepository.listenAudioChunks(sessionId) { sequence, bytes ->
            if (!seen.add(sequence)) return@listenAudioChunks
            Thread {
                try { audioTrack?.write(bytes, 0, bytes.size) } catch (_: Throwable) {}
            }.start()
        }
    }

    private fun stopListening() {
        status.text = "⏳ To'xtatilmoqda..."
        stopButton.isEnabled = false
        AmbientAudioRepository.requestStop()
    }

    private fun detachAudio() {
        audioListener?.remove()
        audioListener = null
        try { audioTrack?.pause() } catch (_: Throwable) {}
        try { audioTrack?.flush() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        currentSessionId = null
        seen.clear()
    }

    override fun onDestroy() {
        AmbientAudioRepository.requestStop()
        requestListener?.remove()
        requestListener = null
        detachAudio()
        super.onDestroy()
    }
}
