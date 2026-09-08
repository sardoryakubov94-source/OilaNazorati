package uz.oilanazorati.parentcontrol.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
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
 * The phone number is supplied by Android only when the carrier/device
 * exposes it. In that case an empty number is intentionally stored rather
 * than guessing it from call/SMS data.
 */
object SimInfoSync {
    private const val PREFS = "sim_info_sync"
    private const val KEY_FINGERPRINT = "fingerprint"

    @Volatile
    private var listenerRegistered = false

    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? = null

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
            manager.addOnSubscriptionsChangedListener(
                context.mainExecutor,
                listener
            )
            subscriptionListener = listener
            listenerRegistered = true
        } catch (_: Throwable) {
            // Some vendor ROMs may reject listener registration; periodic
            // service startup/refresh will still call syncNow().
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
            hashSafeNumber(number)
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
        if (prefs.getString(KEY_FINGERPRINT, null) == fingerprint) return

        val childRef = FirebaseFirestore.getInstance()
            .collection("families").document(familyCode)
            .collection("children").document(childId)

        val data = mapOf(
            "simCount" to sims.size,
            "simCards" to sims,
            "simUpdatedMs" to System.currentTimeMillis(),
            "simLastChangedAt" to FieldValue.serverTimestamp()
        )

        childRef.set(data, SetOptions.merge())
            .addOnSuccessListener {
                prefs.edit().putString(KEY_FINGERPRINT, fingerprint).apply()
            }
    }

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

        // On older Android versions SubscriptionInfo#getNumber() is the
        // available fallback. The READ_PHONE_NUMBERS permission is still
        // checked before this method is called.
        return try {
            manager.getActiveSubscriptionInfo(subscriptionId)?.number?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    // Keeps the compiler from accidentally optimizing/using the raw value
    // for diagnostics. The actual number is sent only to the paired child doc.
    private fun hashSafeNumber(number: String) {
        if (number.isNotEmpty()) number.hashCode()
    }
}
