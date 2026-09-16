package uz.oilanazorati.parentcontrol.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * Reads the currently active mobile subscriptions and publishes their
 * operator/slot/phone number to the paired child's Firestore document.
 *
 * MUHIM (Firestore yozuv kvotasi haqida): oldingi versiyada, agar telefon
 * raqami hech qachon aniqlanmasa (ko'p operatorlarda getPhoneNumber() bo'sh
 * qaytadi — bu O'zbekiston operatorlarida odatiy holat), dublikatni
 * tekshirish sharti (`... && numberAvailable`) tufayli tekshiruv BUTUNLAY
 * o'chib qolardi. Natijada `OnSubscriptionsChangedListener` har safar
 * ishga tushganda (ba'zi qurilmalarda — ayniqsa MIUI/Xiaomi va arzon
 * Android'larda — haqiqiy SIM o'zgarishisiz ham, signal/tarmoq holati
 * sabab tez-tez ishga tushadi) Firestore'ga qayta-qayta yozilardi.
 *
 * Bu versiya ikki mustaqil himoya qatlami bilan qurilgan — ikkalasi ham
 * bir vaqtda ishlaydi, shuning uchun ulardan biri ishlamay qolsa ham
 * (masalan kelajakda kod o'zgarsa), ikkinchisi baribir yozuvni cheklab
 * turadi:
 *
 *  1) FINGERPRINT TEKSHIRUVI — raqam bo'sh yoki to'la bo'lishidan qat'i
 *     nazar, agar oxirgi yozilgan qiymat bilan HECH NARSA o'zgarmagan
 *     bo'lsa, umuman yozilmaydi. Bo'sh raqam ham fingerprint'ning bir
 *     qismi: keyinroq raqam haqiqatan paydo bo'lsa fingerprint o'zgaradi
 *     va faqat o'shanda bitta yangi yozuv ketadi.
 *  2) VAQT CHEGARASI (qattiq himoya) — fingerprint "o'zgargan" ko'rinsa
 *     ham, oxirgi muvaffaqiyatli yozuvdan beri MIN_WRITE_INTERVAL_MS
 *     o'tmagan bo'lsa, yozuv kechiktiriladi. Bu, masalan, kutilmagan OEM
 *     xatti-harakati fingerprint'ni doim "yangi" ko'rsatib yuborsa ham,
 *     bitta qurilma bir kunda eng ko'pi bilan bir nechta yozuv qila
 *     olishini kafolatlaydi (20 ming yozuvlik kunlik limitni hech qachon
 *     yeyolmaydi).
 */
object SimInfoSync {
    private const val PREFS = "sim_info_sync"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_LAST_WRITE_MS = "last_write_ms"

    // Bir xil (yoki "o'zgargan" ko'ringan) ma'lumot bilan bundan tezroq
    // qayta yozilmaydi. 30 daqiqa — haqiqiy SIM almashtirishni ota-onaga
    // yetarlicha tez yetkazadi, lekin har qanday kutilmagan tsikl/xato
    // holatida ham kunlik yozuvni ~48 tagacha cheklaydi (20k limitning
    // ozgina qismi).
    private const val MIN_WRITE_INTERVAL_MS = 30 * 60 * 1000L

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
            // Ba'zi vendor ROM'lar listener ro'yxatdan o'tishni rad etishi
            // mumkin — servis qayta ishga tushganda syncNow() baribir
            // chaqiriladi.
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

        val canReadNumbers = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED

        val sims = subscriptions.map { info ->
            val number = if (canReadNumbers) readPhoneNumber(manager, info.subscriptionId) else ""
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

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowMs = System.currentTimeMillis()

        // 1-himoya: hech narsa o'zgarmagan bo'lsa — yozilmaydi. Raqamning
        // bo'sh bo'lishi bu yerda sabab bo'la olmaydi.
        if (prefs.getString(KEY_FINGERPRINT, null) == fingerprint) return

        // 2-himoya: "o'zgarish" juda tez-tez qayd etilsa ham, oxirgi
        // yozuvdan beri MIN_WRITE_INTERVAL_MS o'tmaguncha yozilmaydi.
        val lastWriteMs = prefs.getLong(KEY_LAST_WRITE_MS, 0L)
        if (nowMs - lastWriteMs < MIN_WRITE_INTERVAL_MS) return

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

    /** Raqamni eng maqbul (SDK'ga qarab to'g'ri API bilan) o'qiydi — bu
     * FAQAT lokal o'qish, Firestore bilan bog'liq emas va hech qanday
     * kvota sarflamaydi. Muvaffaqiyatsiz bo'lsa jim ravishda bo'sh
     * qatorni qaytaradi, chunki raqam ko'p operatorlarda umuman
     * berilmasligi normal holat. */
    private fun readPhoneNumber(manager: SubscriptionManager, subscriptionId: Int): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return try {
                manager.getPhoneNumber(subscriptionId).trim()
            } catch (_: SecurityException) {
                ""
            } catch (_: Throwable) {
                ""
            }
        }

        // Eski Android versiyalarida SubscriptionInfo#getNumber() mavjud
        // yagona muqobil.
        return try {
            manager.getActiveSubscriptionInfo(subscriptionId)?.number?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
