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
import uz.oilanazorati.parentcontrol.model.NotificationEvent
import uz.oilanazorati.parentcontrol.model.SmsEvent

object FirebaseRepo {

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    var familyCode: String? = null
    var childId: String? = null

    /**
     * Yangi oila kodi yaratilganda chaqiriladi. `ownerUid` maydonini
     * yozadi — Firestore qoidalarida shu maydon orqali "bu kodni
     * ANIQ SHU Google hisobi yaratganmi" tekshiriladi. Faqat haqiqiy
     * (anonim bo'lmagan) foydalanuvchi bosishi mumkin bo'lgan tugmadan
     * chaqirilishi kerak — aks holda Firestore qoidasi yozishni rad etadi.
     */
    fun createFamily(code: String, onResult: (Boolean, String?) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onResult(false, "Tizimga kirilmagan")
        db.collection("families").document(code)
            .set(mapOf("ownerUid" to uid, "yaratilganMs" to System.currentTimeMillis()))
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { e -> onResult(false, e.message) }
    }

    /**
     * familyCode/childId hali o'rnatilmagan bo'lsa (masalan, jarayon
     * qayta boshlangan-u, App.restoreSavedPairing hali ulgurmagan yoki
     * qurilma umuman ulanmagan bo'lsa) — bu yerda crash bo'lish o'rniga
     * `null` qaytariladi va chaqiruvchi tomon jim o'tkazib yuboradi.
     * Bunday holatda ma'lumot yo'qoladi, lekin ilova ishlashda davom etadi.
     */
    private fun childCollection(sub: String): com.google.firebase.firestore.CollectionReference? {
        val code = familyCode ?: return null
        val cid = childId ?: return null
        return db.collection("families").document(code)
            .collection("children").document(cid)
            .collection(sub)
    }

    fun logCall(event: CallEvent) {
        childCollection("calls")?.add(event)
    }

    fun logSms(event: SmsEvent) {
        childCollection("sms")?.add(event)
    }

    fun logAppUsage(event: AppUsageEvent) {
        childCollection("app_usage")?.add(event)
    }

    fun logLocation(event: LocationEvent) {
        childCollection("locations")?.add(event)
    }

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

    fun syncSavedContacts(contacts: List<ContactMapping>) {
        val col = childCollection("contacts") ?: return
        col.get().addOnSuccessListener { snap ->
            val batch = db.batch()
            snap.documents.forEach { batch.delete(it.reference) }
            contacts.forEach { c -> batch.set(col.document(c.kontaktHash), c) }
            batch.commit()
        }
    }

    fun listenCallsForDay(dayStartMs: Long, dayEndMs: Long, onChange: (List<CallEvent>) -> Unit) {
        val col = childCollection("calls") ?: return onChange(emptyList())
        col
            .whereGreaterThanOrEqualTo("boshlanishMs", dayStartMs)
            .whereLessThan("boshlanishMs", dayEndMs)
            .orderBy("boshlanishMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(CallEvent::class.java) } ?: emptyList()
                onChange(list)
            }
    }

    fun listenSmsForDay(dayStartMs: Long, dayEndMs: Long, onChange: (List<SmsEvent>) -> Unit) {
        val col = childCollection("sms") ?: return onChange(emptyList())
        col
            .whereGreaterThanOrEqualTo("vaqtMs", dayStartMs)
            .whereLessThan("vaqtMs", dayEndMs)
            .orderBy("vaqtMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(SmsEvent::class.java) } ?: emptyList()
                onChange(list)
            }
    }

    fun listenAppUsageForDay(dayStartMs: Long, dayEndMs: Long, onChange: (List<AppUsageEvent>) -> Unit) {
        val col = childCollection("app_usage") ?: return onChange(emptyList())
        col
            .whereGreaterThanOrEqualTo("boshlanishMs", dayStartMs)
            .whereLessThan("boshlanishMs", dayEndMs)
            .orderBy("boshlanishMs", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(AppUsageEvent::class.java) } ?: emptyList()
                onChange(list)
            }
    }

    fun listenLatestLocation(onChange: (LocationEvent?) -> Unit) {
        val col = childCollection("locations") ?: return onChange(null)
        col
            .orderBy("vaqtMs", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, _ ->
                val loc = snap?.documents?.firstOrNull()?.toObject(LocationEvent::class.java)
                onChange(loc)
            }
    }

    /**
     * Tanlangan kun (yoki istalgan vaqt oralig'i) uchun BARCHA yozilgan
     * joylashuv nuqtalarini (har 30 daqiqada bittadan) vaqt bo'yicha
     * ESKISIDAN YANGISIGA saralab qaytaradi — ro'yxat va xaritada
     * kunlik yo'l tarixini ko'rsatish uchun.
     */
    fun fetchLocationsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<LocationEvent>) -> Unit) {
        val col = childCollection("locations") ?: return onResult(emptyList())
        col
            .whereGreaterThanOrEqualTo("vaqtMs", rangeStartMs)
            .whereLessThan("vaqtMs", rangeEndMs)
            .orderBy("vaqtMs", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(LocationEvent::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun listenSavedContacts(onChange: (List<ContactMapping>) -> Unit) {
        val col = childCollection("contacts") ?: return onChange(emptyList())
        col
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { it.toObject(ContactMapping::class.java) } ?: emptyList()
                onChange(list)
            }
    }

    fun fetchCallsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<CallEvent>) -> Unit) {
        val col = childCollection("calls") ?: return onResult(emptyList())
        col
            .whereGreaterThanOrEqualTo("boshlanishMs", rangeStartMs)
            .whereLessThan("boshlanishMs", rangeEndMs)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(CallEvent::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun fetchSmsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<SmsEvent>) -> Unit) {
        val col = childCollection("sms") ?: return onResult(emptyList())
        col
            .whereGreaterThanOrEqualTo("vaqtMs", rangeStartMs)
            .whereLessThan("vaqtMs", rangeEndMs)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(SmsEvent::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun fetchAppUsageInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<AppUsageEvent>) -> Unit) {
        val col = childCollection("app_usage") ?: return onResult(emptyList())
        col
            .whereGreaterThanOrEqualTo("boshlanishMs", rangeStartMs)
            .whereLessThan("boshlanishMs", rangeEndMs)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(AppUsageEvent::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    // ---------------- Ijtimoiy tarmoq/messenjer bildirishnomalari ----------------

    fun logNotification(event: NotificationEvent) {
        childCollection("notifications")?.add(event)
    }

    fun fetchNotificationsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<NotificationEvent>) -> Unit) {
        val col = childCollection("notifications") ?: return onResult(emptyList())
        col
            .whereGreaterThanOrEqualTo("vaqtMs", rangeStartMs)
            .whereLessThan("vaqtMs", rangeEndMs)
            .get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.mapNotNull { it.toObject(NotificationEvent::class.java) })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }
}
