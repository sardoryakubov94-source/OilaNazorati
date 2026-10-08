package uz.oilanazorati.parentcontrol.risk

import java.util.Locale

data class RiskAnalysis(
    val category: String,
    val severity: String,
    val confidence: Int,
    val summary: String,
    val sensitive: Boolean = false,
    val mediaType: String = "",
    val shouldCaptureEvidence: Boolean = false,
    /** Qaysi so'z(lar) mos tushgani — qisqa dalil matnini shu atrofdan kesib olish uchun. */
    val matchedTerms: List<String> = emptyList()
)

object RiskAnalysisEngine {
    private data class Rule(
        val category: String,
        val weight: Int,
        val terms: List<String>,
        val sensitive: Boolean = false,
        /** "Zaif" so'zlar: bittasi yolg'iz o'zi signal BERMAYDI (kamida 2 ta yoki kuchli so'z bilan birga). */
        val weakTerms: List<String> = emptyList()
    )

    /*
     * Bu birinchi bosqichdagi lokal filtr. U butun suhbatni serverga yubormaydi.
     * Bir nechta mustaqil signal yig'ilganda ishonch oshiriladi.
     *
     * Muhim: "noaniq" media bu yerda sensitive deb belgilanmaydi.
     * Aniq intim signal bo'lmasa media yashirilmasligi kerak.
     */
    private val rules = listOf(
        Rule("ADULT_SEXUAL", 35, listOf(
            "porn", "porno", "pornograf", "seks", "yalang'och", "yalangoch", "nude",
            "nsfw", "erotic", "erotik", "adult content", "adult video", "adult photo",
            "sex video", "sex photo", "голая", "порно", "секс",
            "18+ video", "18+ rasm", "18+ foto", "18+ kino", "18+ film", "18+ видео",
            "18+ контент", "18 plus video"
        ), true, weakTerms = listOf(
            // "18+" kanal/ilova yorlig'ida, "sex" o'zbekchada "ishlab chiqarish sexi",
            // "intim" ko'plab oddiy so'zlarda uchraydi — yolg'iz o'zi signal emas.
            "18+", "18 plus", "sex", "nud", "intim", "intimate"
        )),
        Rule("SEXUAL_IMAGE_REQUEST", 45, listOf(
            "yalang'och rasmingni", "yalangoch rasmingni", "intim rasmingni",
            "intim rasm yubor", "yalang'och foto", "yalangoch foto",
            "private photo", "nude pic", "nudes", "send nudes", "голые фото",
            "интим фото", "пришли интим"
        ), true),
        // MUHIM: "send or" ataylab OLIB TASHLANDI — bu ikki oddiy ingliz
        // so'zi ("send or share", "send or don't" kabi) juda ko'p zararsiz
        // iborada uchraydi, tahdid bilan bog'liq bo'lmasligi mumkin.
        Rule("GROOMING_OR_COERCION", 50, listOf(
            "hech kimga aytma", "sir tut", "don't tell anyone", "keep it secret",
            "rasmni tarqataman", "photo tarqataman", "video tarqataman",
            "if you don't send", "yubormasang", "shantaj",
            "qo'rqitaman", "qorqitaman", "blackmail"
        ), true),
        // MUHIM: "bet", "stavka", "slot" ataylab OLIB TASHLANDI — bular
        // kundalik so'zlashuvda (ingliz tilida "I bet!", "vaqt sloti",
        // kredit "stavka"si kabi) juda tez-tez uchraydi va qimor bilan
        // aloqasi bo'lmasligi mumkin. Qolgan so'zlar (1xbet, betting,
        // casino, qimor va h.k.) allaqachon yetarlicha aniq va xavfsiz.
        Rule("GAMBLING", 40, listOf(
            "1xbet", "1x bet", "melbet", "betting", "casino", "kazino",
            "qimor", "parimatch", "mostbet"
        )),
        Rule("SELF_HARM", 45, listOf(
            "o'z joniga qasd", "oz joniga qasd", "jonimga qasd",
            "o'zimni o'ldir", "ozimni oldir", "self harm", "suicide",
            "kill myself", "cut myself", "самоубийство", "порезать себя"
        )),
        Rule("DRUGS", 40, listOf(
            "narkotik", "giyohvand", "marixuana", "marihuana", "kokain",
            "geroin", "mdma", "наркотик"
        ), weakTerms = listOf("meth", "drug")),
        // MUHIM: "kill" va "murder" ataylab OLIB TASHLANDI — bular
        // o'yinlarda (masalan juda mashhur "Among Us" o'yinida "murder",
        // ko'plab otishma o'yinlarida "kill/nice kill") kundalik atama
        // sifatida doimiy ishlatiladi va haqiqiy zo'ravonlik bilan
        // bog'liq emas. O'zbek/rus tilidagi teng ma'nodagi so'zlar
        // ("o'ldirish", "qotillik", "убийство") qoldirildi — bular
        // o'yin-slengida bunday tez-tez ishlatilmaydi.
        Rule("VIOLENCE", 30, listOf(
            "zo'ravonlik", "zoravonlik", "o'ldirish", "oldirish", "urish",
            "qiynash", "qotillik", "violence", "убийство"
        )),
        Rule("DANGEROUS_CHALLENGE", 30, listOf(
            "dangerous challenge", "xavfli challenge", "xavfli challange",
            "blackout challenge", "choke challenge", "bo'g'ish challenge"
        ))
    )

    /**
     * Uzun ekran matnidan faqat aniqlangan so'z atrofidagi QISQA parchani qaytaradi
     * (ota-ona butun ekran matnini emas, aynan nima sabab bo'lganini ko'rsin).
     */
    fun snippet(text: String, terms: List<String>, radius: Int = 70): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= radius * 2 + 20) return clean
        val idx = terms.map { clean.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
            ?: return clean.take(radius * 2).trimEnd() + "…"
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + radius).coerceAtMost(clean.length)
        return (if (start > 0) "…" else "") + clean.substring(start, end).trim() + (if (end < clean.length) "…" else "")
    }

    // Tizim interfeysi, klaviatura va launcher oynalari o'zi emas, boshqa ilova matnini
    // ko'rsatadi — ular nomi bilan signal yozilsa, bitta ekran 3-4 marta takrorlanadi.
    private val ignoredSourcePackages = listOf("com.android.systemui", "honeyboard", "inputmethod", "keyboard", "launcher")
    private val weakTermSet = setOf("18+", "18 plus", "sex", "nud", "intim", "intimate", "meth", "drug")

    fun isIgnoredPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.ROOT)
        return ignoredSourcePackages.any { p.contains(it) }
    }

    /**
     * Avval saqlangan (eski) so'z-asosli signallar ham yangi qoidalarga mos kelmasa — ro'yxatda
     * ko'rsatilmaydi (ma'lumot o'chirilmaydi). Rasm/video tahlilidan kelgan signallarga tegilmaydi.
     */
    fun isLikelyFalseAlarm(severity: String, summary: String, packageName: String): Boolean {
        if (!summary.contains("(aniqlangan so'z:")) return false
        if (severity != "HIGH") return true
        if (isIgnoredPackage(packageName)) return true
        val terms = Regex("aniqlangan so'z: (.*)\\)").find(summary)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() } ?: return false
        return terms.isNotEmpty() && terms.size < 2 && terms.all { it in weakTermSet }
    }

    fun analyze(vararg rawParts: String): RiskAnalysis? {
        val text = rawParts.filter { it.isNotBlank() }.joinToString(" ").lowercase(Locale.ROOT)
        if (text.isBlank()) return null

        val matches = rules.mapNotNull { rule ->
            val strong = rule.terms.filter { term -> containsWholeTerm(text, term) }
            val weak = rule.weakTerms.filter { term -> containsWholeTerm(text, term) }
            // Bitta zaif so'z ("18+", "sex" va h.k.) yolg'iz o'zi yolg'on signal beradi — talab qilinadi:
            // kamida bitta kuchli so'z YOKI kamida ikkita zaif so'z.
            if (strong.isEmpty() && weak.size < 2) null
            else (strong + weak).let { hits -> Triple(rule, hits.size, hits) }
        }.sortedByDescending { it.first.weight * it.second }

        val top = matches.firstOrNull() ?: return null
        val rule = top.first
        val matched = top.second
        val matchedTerms = top.third

        // Bir signalning o'zi yetarlicha yuqori bo'lmasa, "E'tibor" holati.
        // Bir nechta mos signal va kuchli kalit so'zlar confidence'ni oshiradi.
        val confidence = (55 + rule.weight + (matched - 1) * 8 +
            if (matches.size > 1) 8 else 0).coerceAtMost(98)

        val severity = when {
            confidence >= 88 -> "HIGH"
            confidence >= 72 -> "MEDIUM"
            else -> "LOW"
        }

        // Faqat jiddiy (qizil) signallar qayd etiladi. Sariq (o'rtacha) signallar asosan yolg'on
        // bo'lib chiqqani uchun (masalan "urish", "o'ldirish" kabi oddiy so'zlar) olib tashlandi.
        if (severity != "HIGH") return null

        val summary = when (rule.category) {
            "ADULT_SEXUAL" -> "18+ / seksual mazmundagi faoliyat signali"
            "SEXUAL_IMAGE_REQUEST" -> "Intim rasm/video so'ralishi signali"
            "GROOMING_OR_COERCION" -> "Yashirish, bosim yoki shantajga o'xshash signal"
            "GAMBLING" -> "Qimor/betting faoliyati signali"
            "SELF_HARM" -> "O'z joniga qasd yoki o'ziga zarar mavzusi signali"
            "DRUGS" -> "Narkotik/giyohvandlik mavzusi signali"
            "VIOLENCE" -> "Zo'ravonlik/o'lim mavzusi signali"
            "DANGEROUS_CHALLENGE" -> "Xavfli challenge/harakat signali"
            else -> "Xavf signali"
        }

        return RiskAnalysis(
            category = rule.category,
            severity = severity,
            confidence = confidence,
            // MUHIM: ota-ona (va tuzatuvchi) signal AYNAN NIMA sabab
            // chiqqanini ko'ra olishi kerak — shu sabab qaysi kalit so'z(lar)
            // mos tushgani ochiq yoziladi (yolg'on signalni tezda payqash
            // uchun ham juda muhim).
            summary = "$summary (aniqlangan so'z: ${matchedTerms.joinToString(", ")})",
            sensitive = rule.sensitive,
            shouldCaptureEvidence = confidence >= 80,
            matchedTerms = matchedTerms
        )
    }

    /**
     * "term" so'zi "text" ichida MUSTAQIL SO'Z sifatida (boshqa so'zning
     * bir bo'lagi sifatida emas) uchraydimi, tekshiradi. Oddiy
     * text.contains(term) YOLG'ON SIGNALLARGA olib keladi — masalan "meth"
     * so'zi "inputMETHodservice" ichida, "kill" so'zi "sKILL" ichida ham
     * "topilib" qolaveradi. Bu funksiya atrofidagi belgi harf/raqam
     * bo'lmagan holatlargagina mos deb hisoblaydi.
     */
    private fun containsWholeTerm(text: String, term: String): Boolean {
        if (term.isBlank()) return false
        var idx = text.indexOf(term)
        while (idx >= 0) {
            val before = if (idx > 0) text[idx - 1] else null
            val after = if (idx + term.length < text.length) text[idx + term.length] else null
            val beforeOk = before == null || !before.isLetterOrDigit()
            val afterOk = after == null || !after.isLetterOrDigit()
            if (beforeOk && afterOk) return true
            idx = text.indexOf(term, idx + 1)
        }
        return false
    }

    fun detectMediaMarker(rawParts: List<String>): String {
        val text = rawParts.joinToString(" ").lowercase(Locale.ROOT)
        return when {
            listOf("video", "videoni", "videoga", "movie", "ролик", "видео").any { containsWholeTerm(text, it) } -> "VIDEO"
            listOf("rasm", "rasmini", "foto", "photo", "image", "picture", "📷", "🖼", "фото", "изображение").any { containsWholeTerm(text, it) } -> "IMAGE"
            else -> ""
        }
    }
}
