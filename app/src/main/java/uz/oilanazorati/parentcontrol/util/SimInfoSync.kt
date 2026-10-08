package uz.oilanazorati.parentcontrol.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import uz.oilanazorati.parentcontrol.model.RiskEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * Reads the currently active mobile subscriptions and publishes their
 * operator/slot/phone number to the paired child's Firestore document —
 * bu yozuv aynan ota-onaning panelida SIM ma'lumotini (va SIM
 * almashtirilganini) REAL VAQTDA ko'rsatib turadigan manba, shuning
 * uchun bu qism ataylab saqlab qolindi.
 *
 * TELEFON RAQAMINI ANIQLASH — ESKI VARIANT OLIB TASHLANDI:
 * Android'ning to'g'ridan-to'g'ri `getPhoneNumber()` / SIM raqami API'lari
 * ko'p operatorlarda (jumladan O'zbekistonning aksariyat operatorlarida)
 * umuman ma'lumot bermaydi — bu ilova xatosi emas, operator ma'lumotni
 * SIM/tarmoqqa yozmaganligi sababli. Shu sabab bu API bu yerda BUTUNLAY
 * ishlatilmaydi.
 *
 * O'RNIGA IKKI MUSTAQIL USUL QO'SHILDI (ikkalasi ham quyida
 * applyConfirmedNumber() orqali kiradi, ya'ni ikkalasi ham bir xil
 * kvota-himoyasidan o'tadi):
 *
 *  1-USUL — Google "Phone Number Hint" (Identity API): qurilma
 *     sozlanayotganda (ChildSetupActivity) BITTA marta ko'rsatiladigan
 *     tanlov oynasi — foydalanuvchi bir tegish bilan tasdiqlaydi. Play
 *     Services orqali ishlaydi, ko'pincha to'g'ridan-to'g'ri API'dan
 *     ishonchliroq, lekin baribir kafolat bermaydi (operator/qurilmaga
 *     bog'liq).
 *  2-USUL — Operatorning OMMAVIY "raqamimni bilish" USSD xizmati (masalan
 *     Ucell *120#, Beeline 11010#, UzMobile *110#) orqali background'da
 *     avtomatik urinish. Bu ham kafolatlanmagan (operator/qurilma/firmware
 *     versiyasiga bog'liq), shuning uchun QATTIQ cheklangan: har
 *     subscription uchun bor-yo'g'i MAX_USSD_TRIES marta, urinishlar
 *     orasida kamida USSD_RETRY_COOLDOWN_MS oraliq bilan.
 *
 * Ikkalasi ham muvaffaqiyatsiz bo'lsa, ilova shunchaki raqamsiz davom
 * etadi (slot/operator ma'lumoti baribir keladi) — hech qanday xato yoki
 * qo'shimcha yozuv YO'Q.
 *
 * FIRESTORE YOZUV KVOTASI HAQIDA (asosiy tuzatish, o'zgarishsiz qoldi):
 *  1) Fingerprint tekshiruvi — hech narsa o'zgarmagan bo'lsa, umuman
 *     yozilmaydi (raqam bo'sh yoki to'la bo'lishidan qat'i nazar).
 *  2) MIN_WRITE_INTERVAL_MS — "o'zgarish" juda tez-tez qayd etilsa ham,
 *     bitta qurilma belgilangan oraliqdan tezroq yoza olmaydi. Bu, har
 *     qanday kutilmagan holatda ham, kunlik 20 minglik Firestore yozuv
 *     limitini hech qachon yeyolmasligini kafolatlaydi.
 */
object SimInfoSync {
    private const val PREFS = "sim_info_sync"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_LAST_WRITE_MS = "last_write_ms"
    private const val KEY_CONFIRMED_NUMBER = "confirmed_number" // 1-usul (Hint) natijasi — asosiy/1-SIM uchun
    private const val KEY_CONFIRMED_SUB_ID = "confirmed_sub_id" // Hint raqami qaysi SIM (subscription) uchun tasdiqlangan
    private const val KEY_SIM_SET = "sim_set" // oxirgi muvaffaqiyatli yozilgan SIM'lar to'plami (almashtirishni aniqlash uchun)
    private const val SIM_SWAP_MIN_GAP_MS = 10_000L // SIM almashganda 30 daqiqalik himoya o'rniga faqat shu qisqa oraliq
    private const val KEY_NUMBER_PREFIX = "number_" // 2-usul (USSD) natijasi — subscriptionId bo'yicha aniq
    private const val KEY_USSD_TRIES_PREFIX = "ussd_tries_"
    private const val KEY_USSD_LAST_TRY_PREFIX = "ussd_last_try_"

    private const val MIN_WRITE_INTERVAL_MS = 30 * 60 * 1000L // 30 daqiqa
    private const val MAX_USSD_TRIES = 4
    private const val USSD_RETRY_COOLDOWN_MS = 6 * 60 * 60 * 1000L // 6 soat
    private const val USSD_SIM_STAGGER_MS = 25_000L // 2-SIM so'rovi 1-SIM'dan keyin (bir vaqtda 2 ta USSD sessiya bo'lmaydi)

    // USSD kodlari ro'yxati o'zgarganda oshiriladi — eski (xato kodlar bilan sarflangan)
    // urinishlar nolga tushadi va yangi kodlar bilan qayta sinaladi.
    private const val USSD_CODES_VERSION = 2
    private const val KEY_USSD_CODES_VERSION = "ussd_codes_version"

    // Operatorlarning "o'z raqamimni bilish" USSD kodlari. Har bir operator uchun bir nechta
    // nomzod bo'lishi mumkin — urinishlar navbat bilan sinaydi.
    //  • Mobiuz (UMS): *150#  — operatorning rasmiy USSD ro'yxatida ("узнать свой абонентский номер")
    //  • Beeline:      *148#  — operator kodlari ro'yxatida ("узнать свой номер телефона")
    //  • Ucell:        *450#  ("Kim men" xizmati) — ochiq ro'yxatlarda; *120# eski nomzod sifatida qoldi
    //  • Uzmobile/Humans/Perfectum: tasdiqlangan kod topilmadi — eski nomzod saqlandi (kafolat yo'q)
    private val USSD_NUMBER_CODES: List<Pair<String, List<String>>> = listOf(
        "ucell" to listOf("*450#", "*120#"),
        "beeline" to listOf("*148#"),
        "mobiuz" to listOf("*150#"),
        "ums" to listOf("*150#"),
        "uzmobile" to listOf("*110#"),
        "uztelecom" to listOf("*110#"),
        "perfectum" to listOf("*110#"),
        "humans" to listOf("*110#")
    )

    // O'zbekiston mobil raqamlarining 2 xonali operator kodlari.
    private val UZ_MOBILE_CODES = setOf("20", "33", "50", "55", "70", "71", "77", "88", "90", "91", "93", "94", "95", "97", "98", "99")
    private val NUMBER_REGEX = Regex("""(?<!\d)(?:\+?\s*998)?[\s\-.()]*(\d{2})[\s\-.()]*(\d{3})[\s\-.()]*(\d{2})[\s\-.()]*(\d{2})(?!\d)""")

    @Volatile
    private var listenerRegistered = false

    fun start(context: Context) {
        syncNow(context)
        if (listenerRegistered) return

        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                syncNow(context)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                manager.addOnSubscriptionsChangedListener(listener)
            }
            listenerRegistered = true
        } catch (_: Throwable) {
            // Ba'zi vendor ROM'lar listener ro'yxatdan o'tishni rad etadi —
            // servis qayta ishga tushganda syncNow() baribir chaqiriladi.
        }
    }

    fun syncNow(context: Context) {
        val familyCode = FirebaseRepo.familyCode ?: return
        val childId = FirebaseRepo.childId ?: return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val subscriptions = try {
            manager.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            return
        } catch (_: Throwable) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        resetUssdCountersIfCodesChanged(prefs)

        // 0-usul (bepul, USSD'siz): tizim/SIM raqamni o'zi bersa — shuni olamiz. Ko'p O'zbekiston
        // SIM kartalarida bo'sh keladi, lekin ba'zi telefon/SIM'larda ishlaydi. Faqat haqiqiy
        // O'zbekiston raqamiga o'xshasa qabul qilinadi.
        subscriptions.forEach { info ->
            val key = KEY_NUMBER_PREFIX + info.subscriptionId
            if (prefs.getString(key, "").isNullOrBlank()) {
                tryDirectNumber(context, info)?.let { prefs.edit().putString(key, it).apply() }
            }
        }

        val confirmedPrimaryNumber = prefs.getString(KEY_CONFIRMED_NUMBER, "") ?: ""
        // Eski o'rnatishlarda Hint raqami SIM'ga bog'lanmagan edi — hozirgi 1-SIM'ga bog'laymiz.
        if (confirmedPrimaryNumber.isNotBlank() && !prefs.contains(KEY_CONFIRMED_SUB_ID) && subscriptions.isNotEmpty()) {
            prefs.edit().putInt(KEY_CONFIRMED_SUB_ID, subscriptions[0].subscriptionId).apply()
        }
        val confirmedSubId = prefs.getInt(KEY_CONFIRMED_SUB_ID, Int.MIN_VALUE)

        val sims = subscriptions.map { info ->
            val perSubscriptionNumber = prefs.getString(KEY_NUMBER_PREFIX + info.subscriptionId, "") ?: ""
            // USSD/to'g'ridan-to'g'ri natija (aniq subscription'ga bog'langan) ustunroq; topilmasa
            // Hint raqami — FAQAT shu raqam tasdiqlangan SIM'ga (SIM almashsa eski raqam yangi SIM'ga
            // yopishib qolmasligi uchun).
            val number = perSubscriptionNumber.ifBlank { if (confirmedSubId == info.subscriptionId) confirmedPrimaryNumber else "" }
            mapOf(
                "slot" to info.simSlotIndex,
                "subscriptionId" to info.subscriptionId,
                "operator" to (info.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "Noma'lum operator"),
                "phoneNumber" to number,
                "active" to true
            )
        }

        val fingerprint = sims.joinToString("|") {
            "${it["slot"]}:${it["subscriptionId"]}:${it["operator"]}:${it["phoneNumber"]}"
        }

        val nowMs = System.currentTimeMillis()

        // 1-himoya: hech narsa o'zgarmagan bo'lsa — yozilmaydi.
        val sameAsLastWritten = prefs.getString(KEY_FINGERPRINT, null) == fingerprint

        // SIM'lar to'plami (qo'shildi / olib tashlandi / almashtirildi) — raqam topilishidan farqli,
        // bu haqiqiy SIM almashishi: ota-onaga DARHOL ma'lum qilinadi.
        val simSet = subscriptions.map { it.subscriptionId }.sorted().joinToString(",")
        var previousSimSet = prefs.getString(KEY_SIM_SET, null)
        if (previousSimSet == null && sameAsLastWritten) {
            prefs.edit().putString(KEY_SIM_SET, simSet).apply() // eski o'rnatish: jim bazaviy qiymat
            previousSimSet = simSet
        }
        val simSwapped = previousSimSet != null && previousSimSet != simSet

        if (!sameAsLastWritten) {
            // 2-himoya: "o'zgarish" juda tez-tez qayd etilsa ham, oxirgi yozuvdan beri
            // MIN_WRITE_INTERVAL_MS o'tmaguncha yozilmaydi — LEKIN haqiqiy SIM almashishi bundan
            // mustasno (faqat qisqa SIM_SWAP_MIN_GAP_MS oraliq), aks holda ota-ona 30 daqiqagacha
            // bilmay qolishi mumkin edi.
            val lastWriteMs = prefs.getLong(KEY_LAST_WRITE_MS, 0L)
            val gap = nowMs - lastWriteMs
            if (gap >= MIN_WRITE_INTERVAL_MS || (simSwapped && gap >= SIM_SWAP_MIN_GAP_MS)) {
                val childRef = FirebaseFirestore.getInstance()
                    .collection("families").document(familyCode)
                    .collection("children").document(childId)

                val data = mapOf(
                    "simCount" to sims.size,
                    "simCards" to sims,
                    "simUpdatedMs" to nowMs,
                    "simLastChangedAt" to FieldValue.serverTimestamp()
                )

                childRef.set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        prefs.edit()
                            .putString(KEY_FINGERPRINT, fingerprint)
                            .putLong(KEY_LAST_WRITE_MS, nowMs)
                            .putString(KEY_SIM_SET, simSet)
                            .apply()
                        if (simSwapped) logSimChange(sims)
                    }
            }
        }

        // Raqami hali aniqlanmagan SIM'lar uchun 2-usulni (USSD) background'da,
        // qattiq cheklangan holda sinab ko'ramiz. Bu yerning o'zi HECH QANDAY
        // Firestore yozuvi qilmaydi — faqat muvaffaqiyatli bo'lsa
        // applyConfirmedNumber() chaqiriladi (yuqoridagi himoyalar ostida).
        val handler = Handler(Looper.getMainLooper())
        subscriptions.forEachIndexed { index, info ->
            val hasNumber = !(prefs.getString(KEY_NUMBER_PREFIX + info.subscriptionId, "").isNullOrBlank())
            if (!hasNumber) handler.postDelayed({ maybeAttemptUssdLookup(context, info) }, index * USSD_SIM_STAGGER_MS)
        }
    }

    /** SIM almashganini "Xavfsizlik signallari" ro'yxatiga qizil signal sifatida yozadi. */
    private fun logSimChange(sims: List<Map<String, Any>>) {
        val now = System.currentTimeMillis()
        val current = if (sims.isEmpty()) "SIM kartasi yo'q" else sims.joinToString(", ") { sim ->
            val op = sim["operator"] as? String ?: "Noma'lum operator"
            val num = (sim["phoneNumber"] as? String).orEmpty()
            if (num.isBlank()) op else "$op ($num)"
        }
        FirebaseRepo.logRiskEvent(
            RiskEvent(
                id = "sim_$now",
                category = "SIM_CHANGE",
                severity = "HIGH",
                confidence = 100,
                source = "sim",
                appName = "SIM karta",
                summary = "SIM karta almashtirildi yoki olib tashlandi",
                contextText = "Hozirgi SIM: $current",
                capturedAt = now
            )
        )
    }

    private fun resetUssdCountersIfCodesChanged(prefs: android.content.SharedPreferences) {
        if (prefs.getInt(KEY_USSD_CODES_VERSION, 0) == USSD_CODES_VERSION) return
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_USSD_TRIES_PREFIX) || it.startsWith(KEY_USSD_LAST_TRY_PREFIX) }
            .forEach { editor.remove(it) }
        editor.putInt(KEY_USSD_CODES_VERSION, USSD_CODES_VERSION).apply()
    }

    @Suppress("DEPRECATION")
    private fun tryDirectNumber(context: Context, info: SubscriptionInfo): String? {
        val candidates = mutableListOf<String>()
        try { info.number?.let { candidates += it } } catch (_: Throwable) {}
        try {
            val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            base.createForSubscriptionId(info.subscriptionId).line1Number?.let { candidates += it }
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                context.getSystemService(SubscriptionManager::class.java)
                    ?.getPhoneNumber(info.subscriptionId)?.let { candidates += it }
            } catch (_: Throwable) {}
        }
        return candidates.firstNotNullOfOrNull { extractPhoneNumber(it) }
    }

    /** 1-USUL natijasi: Google Phone Number Hint oynasidan tasdiqlangan
     * raqamni saqlaydi (asosiy/1-SIM uchun) va sync'ni qayta ishga
     * tushiradi — lekin haqiqiy Firestore yozuvi baribir yuqoridagi
     * fingerprint+vaqt himoyasidan o'tadi. */
    fun applyConfirmedNumber(context: Context, number: String) {
        if (number.isBlank()) return
        val firstSubId = try {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
        } catch (_: Throwable) { null }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIRMED_NUMBER, number.trim())
        if (firstSubId != null) editor.putInt(KEY_CONFIRMED_SUB_ID, firstSubId) else editor.remove(KEY_CONFIRMED_SUB_ID)
        editor.apply()
        syncNow(context)
    }

    /** 2-USUL: operatorning ommaviy "raqamimni bilish" USSD xizmatidan
     * background'da avtomatik foydalanishga urinadi. Faqat quyidagi
     * shartlarning barchasi bajarilganda ishga tushadi:
     *  - CALL_PHONE ruxsati berilgan
     *  - operator nomi USSD_NUMBER_CODES ro'yxatida bor
     *  - shu subscription uchun MAX_USSD_TRIES marta urinilmagan
     *  - oxirgi urinishdan beri USSD_RETRY_COOLDOWN_MS o'tgan
     * Muvaffaqiyatsiz bo'lsa yoki shart bajarilmasa — jim o'tkaziladi. */
    private fun maybeAttemptUssdLookup(context: Context, info: SubscriptionInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val triesKey = KEY_USSD_TRIES_PREFIX + info.subscriptionId
        val lastTryKey = KEY_USSD_LAST_TRY_PREFIX + info.subscriptionId

        val tries = prefs.getInt(triesKey, 0)
        if (tries >= MAX_USSD_TRIES) return

        val lastTry = prefs.getLong(lastTryKey, 0L)
        val now = System.currentTimeMillis()
        if (now - lastTry < USSD_RETRY_COOLDOWN_MS) return

        val operatorName = (info.carrierName?.toString() ?: "").lowercase()
        val codes = USSD_NUMBER_CODES.firstOrNull { operatorName.contains(it.first) }?.second ?: return
        val code = codes[tries % codes.size]

        // Urinish sanaladi (muvaffaqiyatli yoki muvaffaqiyatsiz bo'lishidan
        // qat'i nazar) — cheksiz qayta urinishning oldini oladi.
        prefs.edit().putInt(triesKey, tries + 1).putLong(lastTryKey, now).apply()

        try {
            val baseManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            // Aniq shu SIM (subscription) orqali yuboriladi — ikki SIM'li telefonlarda javob
            // boshqa SIM'ga tushib qolmasligi uchun (oldin faqat Android 12+ da shunday edi).
            val tm = try { baseManager.createForSubscriptionId(info.subscriptionId) } catch (_: Throwable) { baseManager }

            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                    extractPhoneNumber(response.toString())?.let { number ->
                        prefs.edit().putString(KEY_NUMBER_PREFIX + info.subscriptionId, number).apply()
                        syncNow(context)
                    }
                }

                override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                    // Jim o'tamiz — 1-usul (Hint) yoki keyingi urinish (agar
                    // limit tugamagan bo'lsa) baribir mavjud.
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {
            // Ba'zi qurilma/operatorlar USSD so'rovini rad etadi — kutilgan holat.
        }
    }

    /** Matndan O'zbekiston mobil raqamini ajratib oladi ("90 123 45 67", "+998901234567",
     * "(90) 123-45-67" va h.k.). Faqat haqiqiy operator kodi (90, 91, 93, 97 ...) bilan
     * boshlansa qabul qilinadi — balans/sana kabi tasodifiy raqamlar adashtirmasin. */
    internal fun extractPhoneNumber(text: String): String? {
        for (m in NUMBER_REGEX.findAll(text)) {
            val code = m.groupValues[1]
            if (code in UZ_MOBILE_CODES) {
                return "+998" + code + m.groupValues[2] + m.groupValues[3] + m.groupValues[4]
            }
        }
        return null
    }
}
