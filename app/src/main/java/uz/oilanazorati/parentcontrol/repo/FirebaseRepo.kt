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
import uz.oilanazorati.parentcontrol.model.SupportMessage
import uz.oilanazorati.parentcontrol.model.PremiumRequest
import uz.oilanazorati.parentcontrol.model.AdminCard

object FirebaseRepo {

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().also { firestore ->
            // Offline navbat (persistence) yoqish — internet yo'q bo'lsa
            // ma'lumotlar qurilmada saqlanib, internet kelganda avtomatik
            // asl vaqt tamg'asi bilan serverga yuboriladi. Bu bir marta
            // chaqiriladi (ilova jarayoni boshida) va qayta-qayta
            // chaqirilsa SDK ogohlantirish beradi.
            try {
                firestore.firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(com.google.firebase.firestore.FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
            } catch (_: Exception) {
                // Agar allaqachon yoqilgan bo'lsa — jim o'tkazib yuboramiz
            }
        }
    }
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

    fun unlinkChild(code: String, childId: String, onResult: (Boolean) -> Unit) {
        db.collection("families").document(code)
            .collection("children").document(childId)
            .delete()
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
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

    // ---------------- Jonli kuzatish (on-demand real-time joylashuv) ----------------

    /**
     * Ota-ona tomonidan chaqiriladi: bola qurilmasining hujjatiga
     * `liveTrackingUntilMs` (hozirgi vaqt + durationMinutes) yozadi.
     * Bola qurilmasidagi MonitorForegroundService shu maydonni
     * tinglab turadi va muddat o'tmaguncha har LIVE_LOCATION_INTERVAL_MS'da
     * (odatiy 30 daqiqa o'rniga) joylashuvni yuboradi.
     */
    fun requestLiveTracking(durationMinutes: Int = 5) {
        val code = familyCode ?: return
        val cid = childId ?: return
        db.collection("families").document(code)
            .collection("children").document(cid)
            .set(
                mapOf("liveTrackingUntilMs" to System.currentTimeMillis() + durationMinutes * 60_000L),
                SetOptions.merge()
            )
    }

    /** Ota-ona jonli kuzatishni muddatidan oldin to'xtatmoqchi bo'lsa. */
    fun stopLiveTracking() {
        val code = familyCode ?: return
        val cid = childId ?: return
        db.collection("families").document(code)
            .collection("children").document(cid)
            .set(mapOf("liveTrackingUntilMs" to 0L), SetOptions.merge())
    }

    /**
     * Bola qurilmasi tomonidan chaqiriladi: o'zining `liveTrackingUntilMs`
     * maydonini real vaqtda kuzatib boradi (parent uni istalgan payt
     * o'zgartirishi mumkin). Chaqiruvchi tomon `remove()` bilan
     * tinglovchini o'chirishi uchun ListenerRegistration qaytariladi.
     */
    fun listenLiveTrackingFlag(onChange: (Long) -> Unit): com.google.firebase.firestore.ListenerRegistration? {
        val code = familyCode ?: return null
        val cid = childId ?: return null
        return db.collection("families").document(code)
            .collection("children").document(cid)
            .addSnapshotListener { snap, _ ->
                onChange(snap?.getLong("liveTrackingUntilMs") ?: 0L)
            }
    }

    fun listenLatestLocation(onChange: (LocationEvent?) -> Unit): com.google.firebase.firestore.ListenerRegistration? {
        val col = childCollection("locations") ?: run { onChange(null); return null }
        return col
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

    // ==================== Premium holati ====================

    /**
     * Joriy Google hisobi Premium'mi — `parents/{uid}` hujjatidagi
     * `premium` maydoniga qarab tekshiradi. Standart holatda `false`
     * (xatolik yoki ma'lumot topilmasa ham xavfsiz tomonga — cheklangan
     * holatga — qaytadi).
     */
    fun checkIsPremium(onResult: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null) { onResult(false); return }
        db.collection("parents").document(uid).get()
            .addOnSuccessListener { doc -> onResult(doc.getBoolean("premium") == true) }
            .addOnFailureListener { onResult(false) }
    }

    // ==================== Support (Bizga yozing) ====================

    fun sendSupportMessage(matn: String, aloqaRaqami: String, onResult: (Boolean) -> Unit) {
        val user = auth.currentUser ?: return onResult(false)
        val msg = SupportMessage(
            fromUid = user.uid,
            fromEmail = user.email.orEmpty(),
            matn = matn,
            aloqaRaqami = aloqaRaqami,
            createdAtMs = System.currentTimeMillis()
        )
        db.collection("support_messages").add(msg)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    /** Faqat joriy foydalanuvchining o'z xabarlari (javoblarini kuzatish uchun). */
    fun listenMySupportMessages(onChange: (List<Pair<String, SupportMessage>>) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onChange(emptyList())
        db.collection("support_messages")
            .whereEqualTo("fromUid", uid)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(SupportMessage::class.java)?.let { doc.id to it }
                }?.sortedByDescending { it.second.createdAtMs } ?: emptyList()
                onChange(list)
            }
    }

    /** ADMIN uchun: barcha support xabarlarini kuzatish. */
    fun listenAllSupportMessages(onChange: (List<Pair<String, SupportMessage>>) -> Unit) {
        db.collection("support_messages")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(SupportMessage::class.java)?.let { doc.id to it }
                }?.sortedByDescending { it.second.createdAtMs } ?: emptyList()
                onChange(list)
            }
    }

    fun replyToSupportMessage(msgId: String, javob: String, onResult: (Boolean) -> Unit) {
        db.collection("support_messages").document(msgId)
            .update(
                mapOf(
                    "adminJavobi" to javob,
                    "holati" to "javob_berildi",
                    "javobVaqtiMs" to System.currentTimeMillis()
                )
            )
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    // ==================== Premium so'rovlari ====================

    fun sendPremiumRequest(izoh: String, skrinshotBase64: String, onResult: (Boolean) -> Unit) {
        val user = auth.currentUser ?: return onResult(false)
        val req = PremiumRequest(
            fromUid = user.uid,
            fromEmail = user.email.orEmpty(),
            tolovIzohi = izoh,
            tolovSkrinshotiBase64 = skrinshotBase64,
            createdAtMs = System.currentTimeMillis()
        )
        db.collection("premium_requests").add(req)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    /** ADMIN uchun: barcha premium so'rovlarini kuzatish. */
    fun listenAllPremiumRequests(onChange: (List<Pair<String, PremiumRequest>>) -> Unit) {
        db.collection("premium_requests")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(PremiumRequest::class.java)?.let { doc.id to it }
                }?.sortedByDescending { it.second.createdAtMs } ?: emptyList()
                onChange(list)
            }
    }

    /** ADMIN: so'rovni tasdiqlaydi VA shu foydalanuvchiga premium beradi. */
    fun approvePremiumRequest(reqId: String, fromUid: String, onResult: (Boolean) -> Unit) {
        val batch = db.batch()
        batch.update(
            db.collection("premium_requests").document(reqId),
            mapOf("holati" to "tolandi", "halQilinganMs" to System.currentTimeMillis())
        )
        batch.set(
            db.collection("parents").document(fromUid),
            mapOf("premium" to true),
            SetOptions.merge()
        )
        batch.commit()
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun rejectPremiumRequest(reqId: String, onResult: (Boolean) -> Unit) {
        db.collection("premium_requests").document(reqId)
            .update(mapOf("holati" to "rad_etildi", "halQilinganMs" to System.currentTimeMillis()))
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    // ==================== Admin to'lov kartalari ====================

    fun listenAdminCards(onChange: (List<AdminCard>) -> Unit) {
        db.collection("admin_cards")
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(AdminCard::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                onChange(list)
            }
    }

    fun addAdminCard(turi: String, raqam: String, egasi: String, onResult: (Boolean) -> Unit) {
        db.collection("admin_cards").add(AdminCard(turi = turi, raqam = raqam, egasi = egasi))
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun deleteAdminCard(cardId: String) {
        db.collection("admin_cards").document(cardId).delete()
    }
}
