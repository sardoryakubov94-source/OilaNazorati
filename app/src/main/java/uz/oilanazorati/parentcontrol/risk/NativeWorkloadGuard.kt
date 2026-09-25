package uz.oilanazorati.parentcontrol.risk

/**
 * MediaRiskAnalyzer (TensorFlow Lite) va Ovoz (WebRTC) ikkalasi ham
 * native (C/C++) kutubxonalarga tayanadi. Ikkalasi AYNI BIR LAHZADA
 * ishga tushsa (masalan ekran-tekshiruv fon oqimida ketayotganda
 * ota-ona "Eshitishni boshlash"ni bossa), ba'zi past-resursli
 * qurilmalarda native darajadagi ziddiyat/xato ehtimoli oshadi.
 *
 * Bu oddiy qulf ikkalasi bir vaqtda ISHLAMASLIGINI kafolatlaydi.
 * TFLite tahlili har doim juda qisqa (bir necha o'nlab millisekund)
 * bo'lgani uchun, WebRTC ishga tushishida sezilarli kechikish
 * bermaydi — faqat ikkalasi tasodifan bir soniyaga to'g'ri kelib
 * qolishining oldini oladi.
 */
object NativeWorkloadGuard {
    private val lock = Any()

    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }
}
