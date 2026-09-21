package uz.oilanazorati.parentcontrol.risk

import java.util.Locale

data class RiskAnalysis(
    val category: String,
    val severity: String,
    val confidence: Int,
    val summary: String,
    val sensitive: Boolean = false,
    val mediaType: String = "",
    val shouldCaptureEvidence: Boolean = false
)

object RiskAnalysisEngine {
    private data class Rule(
        val category: String,
        val weight: Int,
        val terms: List<String>,
        val sensitive: Boolean = false
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
            "18+", "18 plus", "porn", "porno", "pornograf", "seks", "sex",
            "yalang'och", "yalangoch", "nud", "nude", "nsfw", "erotic",
            "erotik", "intim", "intimate", "adult content", "adult video",
            "adult photo", "sex video", "sex photo", "голая", "порно", "секс"
        ), true),
        Rule("SEXUAL_IMAGE_REQUEST", 45, listOf(
            "yalang'och rasmingni", "yalangoch rasmingni", "intim rasmingni",
            "intim rasm yubor", "yalang'och foto", "yalangoch foto",
            "private photo", "nude pic", "nudes", "send nudes", "голые фото",
            "интим фото", "пришли интим"
        ), true),
        Rule("GROOMING_OR_COERCION", 50, listOf(
            "hech kimga aytma", "sir tut", "don't tell anyone", "keep it secret",
            "rasmni tarqataman", "photo tarqataman", "video tarqataman",
            "if you don't send", "yubormasang", "send or", "shantaj",
            "qo'rqitaman", "qorqitaman", "blackmail"
        ), true),
        Rule("GAMBLING", 40, listOf(
            "1xbet", "1x bet", "melbet", "betting", "casino", "kazino",
            "stavka", "bet", "qimor", "slot", "parimatch", "mostbet"
        )),
        Rule("SELF_HARM", 45, listOf(
            "o'z joniga qasd", "oz joniga qasd", "jonimga qasd",
            "o'zimni o'ldir", "ozimni oldir", "self harm", "suicide",
            "kill myself", "cut myself", "самоубийство", "порезать себя"
        )),
        Rule("DRUGS", 40, listOf(
            "narkotik", "giyohvand", "marixuana", "marihuana", "kokain",
            "geroin", "meth", "mdma", "drug", "наркотик"
        )),
        Rule("VIOLENCE", 30, listOf(
            "zo'ravonlik", "zoravonlik", "o'ldirish", "oldirish", "urish",
            "qiynash", "qotillik", "kill", "murder", "violence", "убийство"
        )),
        Rule("DANGEROUS_CHALLENGE", 30, listOf(
            "dangerous challenge", "xavfli challenge", "xavfli challange",
            "blackout challenge", "choke challenge", "bo'g'ish challenge"
        ))
    )

    fun analyze(vararg rawParts: String): RiskAnalysis? {
        val text = rawParts.filter { it.isNotBlank() }.joinToString(" ").lowercase(Locale.ROOT)
        if (text.isBlank()) return null

        val matches = rules.mapNotNull { rule ->
            val count = rule.terms.count { term -> text.contains(term) }
            if (count == 0) null else rule to count
        }.sortedByDescending { it.first.weight * it.second }

        val top = matches.firstOrNull() ?: return null
        val rule = top.first
        val matched = top.second

        // Bir signalning o'zi yetarlicha yuqori bo'lmasa, "E'tibor" holati.
        // Bir nechta mos signal va kuchli kalit so'zlar confidence'ni oshiradi.
        val confidence = (55 + rule.weight + (matched - 1) * 8 +
            if (matches.size > 1) 8 else 0).coerceAtMost(98)

        val severity = when {
            confidence >= 88 -> "HIGH"
            confidence >= 72 -> "MEDIUM"
            else -> "LOW"
        }

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
            summary = summary,
            sensitive = rule.sensitive,
            shouldCaptureEvidence = confidence >= 80
        )
    }

    fun detectMediaMarker(rawParts: List<String>): String {
        val text = rawParts.joinToString(" ").lowercase(Locale.ROOT)
        return when {
            listOf("video", "videoni", "videoga", "movie", "ролик", "видео").any { text.contains(it) } -> "VIDEO"
            listOf("rasm", "rasmini", "foto", "photo", "image", "picture", "📷", "🖼", "фото", "изображение").any { text.contains(it) } -> "IMAGE"
            else -> ""
        }
    }
}
