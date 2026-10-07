package uz.oilanazorati.parentcontrol.service

import uz.oilanazorati.parentcontrol.BuildConfig

/**
 * Agora (Ovoz funksiyasi). Agora Console'dagi loyiha "Secure" rejimda
 * (App Certificate yoqilgan), shuning uchun har ulanishda token yaratiladi.
 *
 * APP_ID va APP_CERT build vaqtida GitHub Secrets'dan (AGORA_APP_ID,
 * AGORA_APP_CERT) olinadi va repoda saqlanmaydi.
 */
object AgoraConfig {
    private const val FALLBACK_APP_ID = "b70d448dd24b4570b12d420e916c62f1"

    val APP_ID: String = BuildConfig.AGORA_APP_ID.ifBlank { FALLBACK_APP_ID }

    private const val TOKEN_TTL_SECONDS = 2 * 60 * 60

    /** Ulanish uchun token. Sertifikat berilmagan bo'lsa null (faqat testing rejim uchun). */
    fun token(channelName: String): String? {
        val cert = BuildConfig.AGORA_APP_CERT
        if (cert.isBlank()) return null
        return AgoraTokenBuilder.buildRtcToken(APP_ID, cert, channelName, 0, TOKEN_TTL_SECONDS)
    }
}
