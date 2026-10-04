package uz.oilanazorati.parentcontrol.ui

import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.AmbientAudioRepository

/**
 * Ota-ona tomonidagi jonli ovoz — Firestore PCM16 transport (AudioTrack).
 *
 * MUHIM TARIXIY ESLATMA (3-oktabr): bu funksiya avval (8-sentabrdan buyon)
 * WebRTC orqali ishlagan, lekin WebRTC native ishga tushirish bosqichida
 * (PeerConnectionFactory.initialize()/JavaAudioDeviceModule) BARCHA sinalgan
 * qurilmalarda, kutubxona versiyasidan qat'i nazar, SIGTRAP bilan qulab
 * tushardi. Sabab turli ehtimollar (ruxsat, R8, crashlytics-ndk, TensorFlow
 * Lite ziddiyati) birma-bir sinalib, hech biri yordam bermadi — demak bu
 * muammo ilovaning o'zida yoki ma'lum bir kutubxona versiyasida emas,
 * balki WebRTC'ning native qatlamiga xos, tuzatib bo'lmaydigan narsa edi.
 *
 * Shu sabab bu funksiya ATAYLAB sodda, sinab ko'rilgan, FAQAT Android'ning
 * o'z standart API'lariga (AudioTrack/AudioRecord) asoslangan usulga
 * qaytarildi — bu usul 7-sentabrgacha ishlatilgan va hech qachon native
 * crash bermagan edi. WebRTC'ga faqat Firebase kvotasini tejash uchun
 * o'tilgan edi (ovoz uzatish Firestore orqali ko'proq yozuv sarflaydi);
 * bu muvozanatni SESSIYA_MAX_MS bilan nazorat qilamiz.
 */
class AmbientListenActivity : AppCompatActivity() {
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private var requestListener: ListenerRegistration? = null
    private var audioListener: ListenerRegistration? = null
    private var audioTrack: AudioTrack? = null
    private var currentRequestId: String? = null
    private var currentSessionId: String? = null
    private var stopping = false
    private val seenSequences = HashSet<Int>()

    private lateinit var status: TextView
    private lateinit var waveform: AudioWaveformView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashlytics.setCustomKey("ovoz_activity", "AmbientListenActivity")
        crashlytics.setCustomKey("ovoz_transport", "firestore_pcm")
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
        waveform = AudioWaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(title)
        root.addView(buildTag)
        root.addView(status)
        root.addView(waveform)
        root.addView(startButton)
        root.addView(stopButton)
        setContentView(root)

        startButton.setOnClickListener {
            uz.oilanazorati.parentcontrol.repo.FirebaseRepo.checkIsPremium { isPremium ->
                if (!isPremium) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("⭐ Premium kerak")
                        .setMessage("Jonli ovoz eshitish faqat premium foydalanuvchilar uchun mavjud.")
                        .setPositiveButton("Premiumni ochish") { _, _ -> startActivity(android.content.Intent(this, PremiumActivity::class.java)) }
                        .setNegativeButton("Bekor qilish", null)
                        .show()
                    return@checkIsPremium
                }
                startListening()
            }
        }
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
        crashlytics.log("Ovoz: so'rov yuborilmoqda (Firestore PCM)")
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
            waveform.setActive(true)
            audioListener = AmbientAudioRepository.listenAudioChunks(sessionId) { sequence, bytes ->
                if (!seenSequences.add(sequence)) return@listenAudioChunks
                try { audioTrack?.write(bytes, 0, bytes.size) } catch (t: Throwable) { crashlytics.recordException(t) }
            }
        } catch (t: Throwable) {
            crashlytics.recordException(t)
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
        waveform.setActive(false)
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
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = false
        } catch (_: Throwable) {}
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
