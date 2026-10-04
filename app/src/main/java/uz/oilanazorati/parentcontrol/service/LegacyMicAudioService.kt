package uz.oilanazorati.parentcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ota-ona "Ovoz" funksiyasi uchun — Firestore PCM16 transport (WebRTC EMAS).
 *
 * MUHIM TARIXIY ESLATMA (3-oktabr): bu funksiya avval WebRTC orqali ishlardi
 * (qarang WebRtcAmbientAudioService — hali ham veb-panel uchun ishlatiladi),
 * lekin Android ilovaning o'zida WebRTC'ning native ishga tushish bosqichi
 * BARCHA sinalgan qurilmalarda SIGTRAP bilan qulab tushardi (kutubxona
 * versiyasidan qat'i nazar). Shu sabab Android ilovaning "Ovoz" funksiyasi
 * ataylab oddiy, faqat Android'ning o'z standart API'lariga (AudioRecord)
 * asoslangan, 7-sentabrgacha ishlatilgan va hech qachon native crash
 * bermagan usulga qaytarildi.
 *
 * `transport != "webrtc"` tekshiruvi orqali bu xizmat veb-panelning WebRTC
 * so'rovlariga ARALASHMAYDI — ikkalasi bir xil Firestore hujjatida bemalol
 * yonma-yon ishlay oladi.
 *
 * Hozirgi batareya-tejash arxitekturasi saqlangan: bu xizmat FAQAT haqiqiy
 * so'rov kelganda ishga tushadi (MonitorForegroundService orqali) va band
 * bo'lmay qolgach, o'z-o'zini to'xtatadi.
 */
class LegacyMicAudioService : Service() {
    private val db = FirebaseFirestore.getInstance()
    private var requestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val recording = AtomicBoolean(false)
    private var activeRequestId: String? = null
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleStopRunnable = Runnable { stopSelf() }
    private val IDLE_STOP_DELAY_MS = 30_000L

    companion object {
        const val CHANNEL_ID = "oila_nazorati_mic"
        const val NOTIFICATION_ID = 510
        const val SAMPLE_RATE = 16000
        // MUHIM (Firebase kvotasi): har bo'lak — bitta Firestore yozuvi.
        // Bepul tarifda kuniga ~20 000 yozuv bor, BARCHA funksiyalar
        // (SMS, qo'ng'iroq, skrinshot va h.k.) shu limitni baham ko'radi.
        // 500ms bilan sekundiga 2 yozuv bo'lib, bu limitni 2-3 soatda
        // tugatib qo'yar edi (WebRTC'ga o'tishning asl sababi aynan shu
        // edi). 2000ms (2 soniya) bilan sekundiga 0.5 yozuv — ya'ni 4
        // baravar kamroq, kuniga ~4 soatlik tinglashga yetadi va "jonli"
        // tuyg'usi deyarli yo'qolmaydi (ovoz 2 soniya kechikish bilan
        // yetib boradi, suhbatni emas, atrof-muhitni kuzatish uchun bu
        // yetarlicha tez).
        const val CHUNK_MS = 2000
        // Bitta sessiya (bitta "Eshitishni boshlash" bosilishi) uchun
        // xavfsizlik chegarasi — 10 daqiqa, 2000ms bilan bu atigi ~300
        // ta yozuv (avvalgi 500ms'dagi ~1200 taga nisbatan 4 baravar kam).
        const val MAX_SESSION_MS = 10 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, idleNotification(), foregroundTypes())
        listenForRequests()
        idleHandler.postDelayed(idleStopRunnable, IDLE_STOP_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun foregroundTypes(): Int = if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    private fun listenForRequests() {
        val family = FirebaseRepo.familyCode ?: return
        val child = FirebaseRepo.childId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        requestListener = db.collection("families").document(family)
            .collection("children").document(child)
            .collection("mic_requests").document("current")
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
                val data = snap.data.orEmpty()
                // Veb-panelning WebRTC so'rovlariga aralashmaymiz.
                if (data["transport"] == "webrtc") return@addSnapshotListener
                val requestId = data["requestId"] as? String ?: return@addSnapshotListener
                when (data["status"] as? String) {
                    "requested" -> if (!recording.get()) startSession(requestId)
                    "stop_requested" -> if (activeRequestId == requestId) stopSession("stopped")
                }
            }
    }

    private fun startSession(requestId: String) {
        idleHandler.removeCallbacks(idleStopRunnable)
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateRequest(requestId, "failed", error = "Mikrofon ruxsati berilmagan")
            idleHandler.postDelayed(idleStopRunnable, IDLE_STOP_DELAY_MS)
            return
        }
        val family = FirebaseRepo.familyCode ?: return
        val child = FirebaseRepo.childId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val sessionId = "mic_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        activeRequestId = requestId
        recording.set(true)
        updateActiveNotification()
        updateRequest(requestId, "active", sessionId = sessionId)

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            stopSession("failed", "AudioRecord buffer xatosi")
            return
        }
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2)
        try {
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                stopSession("failed", "Mikrofon ishga tushmadi")
                return
            }
            recorder?.startRecording()
        } catch (e: Throwable) {
            stopSession("failed", e.message ?: "Mikrofon xatosi")
            return
        }

        worker = Thread {
            val chunkBytes = SAMPLE_RATE * 2 * CHUNK_MS / 1000
            val buffer = ByteArray(chunkBytes)
            var sequence = 0
            val startedAt = System.currentTimeMillis()
            try {
                while (recording.get() && System.currentTimeMillis() - startedAt < MAX_SESSION_MS) {
                    var offset = 0
                    while (offset < buffer.size && recording.get()) {
                        val read = recorder?.read(buffer, offset, buffer.size - offset) ?: -1
                        if (read <= 0) break
                        offset += read
                    }
                    if (offset > 0 && recording.get()) {
                        val bytes = buffer.copyOf(offset)
                        db.collection("families").document(family)
                            .collection("children").document(child)
                            .collection("mic_audio").document("${sessionId}_$sequence")
                            .set(mapOf("sessionId" to sessionId, "sequence" to sequence, "createdAt" to System.currentTimeMillis(), "sampleRate" to SAMPLE_RATE, "channels" to 1, "encoding" to "pcm16", "audio" to Blob.fromBytes(bytes)))
                        sequence++
                    }
                }
            } catch (t: Throwable) {
                if (recording.get()) updateRequest(requestId, "failed", error = t.message ?: "Audio uzatish xatosi")
            } finally {
                if (recording.get()) stopSession("stopped", "Vaqt limiti tugadi")
            }
        }.also { it.start() }
    }

    private fun stopSession(status: String, error: String? = null) {
        recording.set(false)
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        worker = null
        val requestId = activeRequestId
        activeRequestId = null
        if (requestId != null) updateRequest(requestId, status, error = error)
        restoreIdleNotification()
        // Battareya: chaqiruv tugagach xizmatni abadiy ishlab turishga
        // qoldirmaymiz — MonitorForegroundService keyingi so'rovda uni
        // darhol qayta ishga tushiradi.
        idleHandler.removeCallbacks(idleStopRunnable)
        idleHandler.postDelayed(idleStopRunnable, IDLE_STOP_DELAY_MS)
    }

    private fun updateRequest(requestId: String, status: String, sessionId: String? = null, error: String? = null) {
        val family = FirebaseRepo.familyCode ?: return
        val child = FirebaseRepo.childId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val data = mutableMapOf<String, Any>("requestId" to requestId, "status" to status, "updatedAt" to System.currentTimeMillis())
        if (sessionId != null) data["sessionId"] = sessionId
        if (error != null) data["error"] = error.take(200)
        db.collection("families").document(family).collection("children").document(child).collection("mic_requests").document("current").update(data)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Ovoz nazorati", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun idleNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_blank).setContentTitle("Oila Nazorati").setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).setShowWhen(false).build()

    private fun activeNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_blank).setContentTitle("🎙️ Mikrofon faol").setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).setShowWhen(false).build()

    private fun updateActiveNotification() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, activeNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIFICATION_ID, activeNotification())
    }

    private fun restoreIdleNotification() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, idleNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIFICATION_ID, idleNotification())
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleStopRunnable)
        requestListener?.remove()
        recording.set(false)
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
