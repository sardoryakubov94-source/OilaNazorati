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
    private const val KEY_NUMBER_PREFIX = "number_" // 2-usul (USSD) natijasi — subscriptionId bo'yicha aniq
    private const val KEY_USSD_TRIES_PREFIX = "ussd_tries_"
    private const val KEY_USSD_LAST_TRY_PREFIX = "ussd_last_try_"

    private const val MIN_WRITE_INTERVAL_MS = 30 * 60 * 1000L // 30 daqiqa
    private const val MAX_USSD_TRIES = 3
    private const val USSD_RETRY_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 soat

    // Operatorlarning OMMAVIY "raqamimni bilish" USSD kodlari. Manba:
    // operatorlarning ochiq yordam sahifalari. Bular vaqt o'tishi bilan
    // o'zgarishi mumkin — shuning uchun bu FAQAT qo'shimcha, ixtiyoriy
    // urinish, yagona manba emas (1-usul asosiy hisoblanadi).
    private val USSD_NUMBER_CODES = listOf(
        "ucell" to "*120#",
        "beeline" to "11010#",
        "uzmobile" to "*110#",
        "mobiuz" to "*110#",
        "perfectum" to "*110#",
        "humans" to "*110#"
    )

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
        val confirmedPrimaryNumber = prefs.getString(KEY_CONFIRMED_NUMBER, "") ?: ""

        val sims = subscriptions.mapIndexed { index, info ->
            val perSubscriptionNumber = prefs.getString(KEY_NUMBER_PREFIX + info.subscriptionId, "") ?: ""
            // 2-usul (USSD, aniq subscription'ga bog'langan) ustunroq;
            // topilmasa 1-usul (Hint, faqat 1-SIM uchun) ishlatiladi.
            val number = perSubscriptionNumber.ifBlank { if (index == 0) confirmedPrimaryNumber else "" }
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
        if (!sameAsLastWritten) {
            // 2-himoya: "o'zgarish" juda tez-tez qayd etilsa ham, oxirgi
            // yozuvdan beri MIN_WRITE_INTERVAL_MS o'tmaguncha yozilmaydi.
            val lastWriteMs = prefs.getLong(KEY_LAST_WRITE_MS, 0L)
            if (nowMs - lastWriteMs >= MIN_WRITE_INTERVAL_MS) {
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
                            .apply()
                    }
            }
        }

        // Raqami hali aniqlanmagan SIM'lar uchun 2-usulni (USSD) background'da,
        // qattiq cheklangan holda sinab ko'ramiz. Bu yerning o'zi HECH QANDAY
        // Firestore yozuvi qilmaydi — faqat muvaffaqiyatli bo'lsa
        // applyConfirmedNumber() chaqiriladi (yuqoridagi himoyalar ostida).
        subscriptions.forEach { info ->
            val hasNumber = !(prefs.getString(KEY_NUMBER_PREFIX + info.subscriptionId, "").isNullOrBlank())
            if (!hasNumber) maybeAttemptUssdLookup(context, info)
        }
    }

    /** 1-USUL natijasi: Google Phone Number Hint oynasidan tasdiqlangan
     * raqamni saqlaydi (asosiy/1-SIM uchun) va sync'ni qayta ishga
     * tushiradi — lekin haqiqiy Firestore yozuvi baribir yuqoridagi
     * fingerprint+vaqt himoyasidan o'tadi. */
    fun applyConfirmedNumber(context: Context, number: String) {
        if (number.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIRMED_NUMBER, number.trim())
            .apply()
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
        val code = USSD_NUMBER_CODES.firstOrNull { operatorName.contains(it.first) }?.second ?: return

        // Urinish sanaladi (muvaffaqiyatli yoki muvaffaqiyatsiz bo'lishidan
        // qat'i nazar) — cheksiz qayta urinishning oldini oladi.
        prefs.edit().putInt(triesKey, tries + 1).putLong(lastTryKey, now).apply()

        try {
            val baseManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val tm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { baseManager.createForSubscriptionId(info.subscriptionId) } catch (_: Throwable) { baseManager }
            } else baseManager

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

    /** Operator USSD javobidan O'zbekiston mobil raqamiga o'xshash
     * ketma-ketlikni ajratib oladi (heuristika — operator javob matni
     * turlicha bo'lishi mumkin, shuning uchun kafolat yo'q). */
    private fun extractPhoneNumber(responseText: String): String? {
        val digitsOnly = responseText.filter { it.isDigit() }
        val match = Regex("998\\d{9}").find(digitsOnly)
            ?: Regex("9\\d{8}").find(digitsOnly)
            ?: return null
        val digits = match.value
        return if (digits.startsWith("998")) "+$digits" else "+998$digits"
    }
}
