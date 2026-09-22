package uz.oilanazorati.parentcontrol.risk

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Ekrandan olingan screenshot/kadrning O'ZINI (piksellarini) QURILMANING
 * ICHIDA tahlil qiladi — RiskAnalysisEngine'dan farqli o'laroq, bu matnga
 * emas, rasmning tarkibiga qaraydi. Shu orqali "matn yo'q, faqat 18+ rasm"
 * holatlari ham aniqlanadi.
 *
 * MUHIM: rasmning o'zi hech qachon serverga yuborilmaydi. Faqat shu tahlil
 * natijasi (kategoriya + ishonchlilik %) Firebase'ga RiskEvent sifatida
 * yoziladi. Kerak bo'lsa, dalil sifatida saqlanadigan screenshot xiralashib
 * (blur) saqlanadi — buni chaqiruvchi (AccessibilityScreenshotService) hal
 * qiladi.
 *
 * Model: ochiq manbali, MIT litsenziyali "nsfw_model" (GantMan, MobileNetV2
 * asosida) — https://github.com/GantMan/nsfw_model — 5 sinf bo'yicha
 * o'qitilgan: drawings / hentai / neutral / porn / sexy.
 *
 * Chegara qiymatlar ataylab yuqori qo'yilgan: maqsad — noto'g'ri signal
 * (false positive) minimal bo'lishi, faqat model ANIQ ishonch bilan
 * aytganda ota-onaga xabar boradi. 100% aniqlik kafolatlanmaydi — bu
 * qurilmadagi birinchi bosqich filtr, xolos.
 */
object MediaRiskAnalyzer {

    data class MediaVerdict(
        val category: String,
        val severity: String,
        val confidence: Int,
        val summary: String,
        // true bo'lsa — model DEYARLI 100% ishonch bilan aytgan (tasdiqlangan
        // holat): shu holdagina dalil xiralashtiriladi/ko'rsatilmaydi.
        // false bo'lsa — shubhali, lekin ANIQ EMAS: RiskEvent yoziladi, LEKIN
        // dalil OCHIQ (xiralashtirilmagan) holda ota-onaga ko'rsatiladi — xuddi
        // matn-asosidagi tahlildagi kabi, ota-ona o'zi ko'rib xulosa chiqarsin.
        val sensitive: Boolean
    )

    private const val MODEL_FILE = "nsfw_classifier.tflite"
    private const val INPUT_SIZE = 224
    private val LABELS = listOf("drawings", "hentai", "neutral", "porn", "sexy")

    // Pastki chegara: shubha uyg'otadi, RiskEvent yoziladi, LEKIN dalil ochiq
    // ko'rsatiladi (ota-ona o'zi ko'radi va baholaydi — noaniq holat).
    private const val PORN_THRESHOLD = 0.75f
    private const val HENTAI_THRESHOLD = 0.80f
    private const val SEXY_THRESHOLD = 0.90f

    // Yuqori chegara: model DEYARLI to'liq ishonch bilan aytgan — faqat shu
    // holatda "tasdiqlangan" deb hisoblanadi va dalil yashiriladi/xiralanadi.
    // "sexy" (ochiq/intim, lekin yalang'och emas) sinfi hech qachon
    // "tasdiqlangan" darajaga chiqarilmaydi — u har doim ochiq ko'rsatiladi,
    // chunki bu haqiqiy 18+ tasvir emas, faqat shubhali/chegaraviy holat.
    private const val PORN_CONFIRMED_THRESHOLD = 0.95f
    private const val HENTAI_CONFIRMED_THRESHOLD = 0.95f

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var loadFailed = false
    private val lock = Any()

    private fun getInterpreter(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (loadFailed) return null
        synchronized(lock) {
            interpreter?.let { return it }
            if (loadFailed) return null
            return runCatching {
                context.assets.openFd(MODEL_FILE).use { afd ->
                    val buffer = afd.createInputStream().channel.use { channel ->
                        channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                    }
                    Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
                }
            }.onFailure { loadFailed = true }
                .getOrNull()
                ?.also { interpreter = it }
        }
    }

    /**
     * Bitmapni tahlil qiladi va xavf topilsa xulosa qaytaradi, aks holda
     * null. MUHIM: bu og'ir (bir necha o'nlab millisekund) amal — chaqiruvchi
     * buni albatta FON OQIMIDA (main/UI thread'dan tashqarida) chaqirishi
     * kerak, aks holda ilova "qotib qolishi" mumkin.
     */
    fun analyze(context: Context, bitmap: Bitmap): MediaVerdict? {
        val model = getInterpreter(context.applicationContext) ?: return null
        return runCatching {
            val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) bitmap
                else Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

            val input = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
                .apply { order(ByteOrder.nativeOrder()) }
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
            input.rewind()
            if (scaled !== bitmap) scaled.recycle()

            val output = Array(1) { FloatArray(LABELS.size) }
            model.run(input, output)
            toVerdict(LABELS.zip(output[0].toList()).toMap())
        }.getOrNull()
    }

    private fun toVerdict(scores: Map<String, Float>): MediaVerdict? {
        val porn = scores["porn"] ?: 0f
        val hentai = scores["hentai"] ?: 0f
        val sexy = scores["sexy"] ?: 0f
        return when {
            porn >= PORN_CONFIRMED_THRESHOLD ->
                MediaVerdict("ADULT_MEDIA", "HIGH", (porn * 100).toInt(), "Ekranda aniq 18+ tasvir aniqlandi", sensitive = true)
            hentai >= HENTAI_CONFIRMED_THRESHOLD ->
                MediaVerdict("ADULT_MEDIA", "HIGH", (hentai * 100).toInt(), "Ekranda aniq 18+ (chizma/anime) tasvir aniqlandi", sensitive = true)
            porn >= PORN_THRESHOLD ->
                MediaVerdict("ADULT_MEDIA", "HIGH", (porn * 100).toInt(), "Ekranda 18+ bo'lishi mumkin bo'lgan tasvir aniqlandi", sensitive = false)
            hentai >= HENTAI_THRESHOLD ->
                MediaVerdict("ADULT_MEDIA", "HIGH", (hentai * 100).toInt(), "Ekranda 18+ (chizma/anime) bo'lishi mumkin bo'lgan tasvir aniqlandi", sensitive = false)
            sexy >= SEXY_THRESHOLD ->
                MediaVerdict("SUGGESTIVE_MEDIA", "MEDIUM", (sexy * 100).toInt(), "Ekranda ochiq/intim tasvir aniqlandi", sensitive = false)
            else -> null
        }
    }
}
