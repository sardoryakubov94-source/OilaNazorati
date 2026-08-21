package uz.oilanazorati.parentcontrol.repo

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import uz.oilanazorati.parentcontrol.model.AppUsageEvent
import uz.oilanazorati.parentcontrol.model.CallEvent
import uz.oilanazorati.parentcontrol.model.ChildProfile
import uz.oilanazorati.parentcontrol.model.ContactMapping
import uz.oilanazorati.parentcontrol.model.LocationEvent
import uz.oilanazorati.parentcontrol.model.SmsEvent

/**
 * Barcha Firestore o'qish/yozish shu klass orqali amalga oshiriladi.
 *
 * Firestore tuzilishi:
 *   families/{familyCode}/children/{childId}/calls/{autoId}
 *   families/{familyCode}/children/{childId}/sms/{autoId}
 *   families/{familyCode}/children/{childId}/app_usage/{autoId}
 *   families/{familyCode}/children/{childId}/locations/{autoId}
 *
 * familyCode — ota-ona ilovani birinchi ochganda generatsiya qiladigan
 * 6 xonali kod (masalan "4F9K21"). Bola qurilmasida sozlashda shu kod
 * kiritiladi va ikkala tomon shu kod orqali bir-biriga bog'lanadi.
 */
object FirebaseRepo {

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // ---- Kontekst: joriy oila kodi va bola identifikatori ----
    var familyCode: String? = null
    var childId: String? = null

    private fun childCollection(sub: String) =
        db.collection("families").document(requireNotNull(familyCode) { "familyCode o'rnatilmagan" })
            .collection("children").document(requireNotNull(childId) { "childId o'rnatilmagan" })
            .collection(sub)

    // ================= YOZISH (bola qurilmasidan) =================

    fun logCall(event: CallEvent) {
        childCollection("calls").add(event)
    }

    fun logSms(event: SmsEvent) {
        childCollection("sms").add(event)
    }

    fun logAppUsage(event: AppUsageEvent) {
        childCollection("app_usage").add(event)
    }

    fun logLocation(event: LocationEvent) {
        childCollection("locations").add(event)
    }

    /**
     * Bola qurilmasi oila kodiga ULANGANDA (pairing paytida) chaqiriladi.
     * Ota-ona kiritgan ismni families/{code}/children/{childId} hujjatiga
     * yozadi — shu orqali BITTA oila kodiga bir nechta farzand ulansa ham,
     * ota-ona ularni ismi bo'yicha farqlab, ekranda tanlay oladi.
     */
    fun saveChildProfile(name: String) {
        val code = familyCode ?: return
        val cid = childId ?: return
        db.collection("families").document(code)
            .collection("children").document(cid)
            .set(
                mapOf(
                    "nomi" to name,
                    "yaratilganMs" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
    }

    /**
     * Berilgan oila kodiga ulangan BARCHA farzandlar ro'yxatini
     * (childId + ism) bir martalik o'qiydi — ota-ona ekranida
     * "qaysi farzand?" tanlovi uchun ishlatiladi.
     */
    fun fetchChildren(code: String, onResult: (List<Pair<String, String>>) -> Unit) {
        db.collection("families").document(code)
            .collection("children")
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.map { doc ->
                    val name = doc.getString("nomi")?.takeIf { it.isNotBlank() }
                        ?: "Farzand (${doc.id.take(5)})"
                    doc.id to name
                }
                onResult(list)
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    /**
     * Bola qurilmasidagi ISM BILAN SAQLANGAN kontaktlar ro'yxatini
     * (faqat nomi + anonim kontaktHash, RAQAMSIZ) Firestore'ga yozadi.
     * Saqlanmagan (notanish) raqamlar bu yerga umuman kelmaydi — ular
     * ContactSyncHelper darajasida allaqachon filtrlangan bo'ladi.
     * Har chaqirilganda eskisini almashtiradi (kontakt o'chirilsa/
     * qo'shilsa ham ro'yxat yangilanib tursin uchun).
     */
    fun syncSavedContacts(contacts: List<ContactMapping>) {
        val col = childCollection("contacts")
        col.get().addOnSuccessListener { snap ->
            val batch = db.batch()
            snap.documents.forEach { batch.delete(it.reference) }
            contacts.forEach { c -> batch.set(col.document(c.kontaktHash), c) }
            batch.commit()
        }
    }

    // ================= O'QISH (ota-ona panelida) =================

    fun listenCallsForDay(dayStartMs: Long, dayEndMs: Long, onChange: (List<CallEvent>) -> Unit) {
        childCollection("calls")
            .whereGreaterThanOrEqualTo("boshlanishMs", dayStartMs)
            .whereLessThan("boshlanishMs", dayEndMs)
            .orderBy("boshlanishMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(CallEvent::class.java) } ?: emptyList()
                onChange(list)
            }
    }

    fun listenSmsForDay(dayStartMs: Long, dayEndMs: Long, onChange: (List<SmsEvent>) -> Unit) {
        childCollection("sms")
            .whereGreaterThanOrEqualTo("vaqtMs", dayStartMs)
            .whereLessThan("vaqtMs", dayEndMs)
            .orderBy("vaqtMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(SmsEvent::class.java) } ?: emptyList()
                onChange(list)
            }
    }

    fun listenAppUsageForDay(dayStartMs: Long, dayEndMs: Long, onChange: (List<AppUsageEvent>) -> Unit) {
        childCollection("app_usage")
            .whereGreaterThanOrEqualTo("boshlanishMs", dayStartMs)
            .whereLessThan("boshlanishMs", dayEndMs)
            .orderBy("boshlanishMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(AppUsageEvent::class.java) } ?: emptyList()
                onChange(list)
            }
    }

    fun listenLatestLocation(onChange: (LocationEvent?) -> Unit) {
        childCollection("locations")
            .orderBy("vaqtMs", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, _ ->
                val loc = snap?.documents?.firstOrNull()?.toObject(LocationEvent::class.java)
                onChange(loc)
            }
    }

    /** Ota-ona ekranida: saqlangan kontaktlar ro'yxati (ism + rang uchun hash). */
    fun listenSavedContacts(onChange: (List<ContactMapping>) -> Unit) {
        childCollection("contacts")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(ContactMapping::class.java) } ?: emptyList()
                onChange(list)
            }
    }

    // ================= TREND STATISTIKASI (bir martalik so'rov, N kunlik oraliq) =================

    /** rangeStartMs..rangeEndMs oralig'idagi barcha qo'ng'iroqlarni bir martalik o'qiydi. */
    fun fetchCallsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<CallEvent>) -> Unit) {
        childCollection("calls")
            .whereGreaterThanOrEqualTo("boshlanishMs", rangeStartMs)
            .whereLessThan("boshlanishMs", rangeEndMs)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(CallEvent::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    /** rangeStartMs..rangeEndMs oralig'idagi barcha SMS hodisalarini bir martalik o'qiydi. */
    fun fetchSmsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<SmsEvent>) -> Unit) {
        childCollection("sms")
            .whereGreaterThanOrEqualTo("vaqtMs", rangeStartMs)
            .whereLessThan("vaqtMs", rangeEndMs)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(SmsEvent::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    /** rangeStartMs..rangeEndMs oralig'idagi barcha ilova ishlatilish hodisalarini bir martalik o'qiydi. */
    fun fetchAppUsageInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<AppUsageEvent>) -> Unit) {
        childCollection("app_usage")
            .whereGreaterThanOrEqualTo("boshlanishMs", rangeStartMs)
            .whereLessThan("boshlanishMs", rangeEndMs)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(AppUsageEvent::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }
}
