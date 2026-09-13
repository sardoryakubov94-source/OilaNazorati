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
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

object FirebaseRepo {

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().also { firestore ->
            try {
                firestore.firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(com.google.firebase.firestore.FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
            } catch (_: Exception) {}
        }
    }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val pairingExecutor = Executors.newCachedThreadPool()

    var familyCode: String? = null
    var childId: String? = null

    fun createFamily(code: String, onResult: (Boolean, String?) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onResult(false, "Tizimga kirilmagan")
        db.collection("families").document(code)
            .set(mapOf("ownerUid" to uid, "yaratilganMs" to System.currentTimeMillis()))
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { e -> onResult(false, e.message) }
    }

    fun findOrCreateFamilyForCurrentUser(onResult: (String?) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onResult(null)
        db.collection("families").whereEqualTo("ownerUid", uid).get()
            .addOnSuccessListener { snap ->
                val existing = snap.documents.firstOrNull()?.id
                if (existing != null) {
                    onResult(existing)
                } else {
                    val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
                    createFamily(code) { ok, _ -> onResult(if (ok) code else null) }
                }
            }
            .addOnFailureListener { onResult(null) }
    }

    /**
     * Bola ulanishi uchun Firestore Android SDK yozuvini ishlatmaymiz.
     * Ba'zi mobil tarmoqlarda Firestore gRPC transporti javobsiz osilib
     * qolishi mumkin. Shu sabab aynan pairing yozuvi Firestore REST API
     * orqali yuboriladi. Auth ID token ishlatilgani uchun Firestore Rules
     * baribir to'liq qo'llanadi. Qolgan Firestore funksiyalari o'zgarmaydi.
     */
    fun joinFamily(code: String, childName: String, onResult: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        val uid = user?.uid
        if (uid.isNullOrBlank()) {
            onResult(false, "Firebase foydalanuvchisi yaratilmadi")
            return
        }

        val normalized = code.trim().uppercase()
        if (normalized.length != 6) {
            onResult(false, "Oila kodi 6 belgidan iborat bo'lishi kerak")
            return
        }

        user.getIdToken(false)
            .addOnSuccessListener { tokenResult ->
                val idToken = tokenResult.token
                if (idToken.isNullOrBlank()) {
                    onResult(false, "Firebase avtorizatsiya tokeni olinmadi")
                    return@addOnSuccessListener
                }

                pairingExecutor.execute {
                    var connection: HttpURLConnection? = null
                    try {
                        val safeCode = URLEncoder.encode(normalized, "UTF-8").replace("+", "%20")
                        val safeUid = URLEncoder.encode(uid, "UTF-8").replace("+", "%20")
                        val url = URL(
                            "https://firestore.googleapis.com/v1/projects/oilanazorat-3c8de/databases/(default)/documents/families/$safeCode/children/$safeUid"
                        )
                        connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "PATCH"
                            connectTimeout = 10_000
                            readTimeout = 10_000
                            doOutput = true
                            setRequestProperty("Authorization", "Bearer $idToken")
                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        }

                        val body = JSONObject().apply {
                            put("fields", JSONObject().apply {
                                put("nomi", JSONObject().put("stringValue", childName))
                                put("childUid", JSONObject().put("stringValue", uid))
                                put("yaratilganMs", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
                            })
                        }.toString()

                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        val status = connection.responseCode
                        val responseText = try {
                            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        } catch (_: Exception) { "" }

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (status in 200..299) {
                                familyCode = normalized
                                childId = uid
                                onResult(true, null)
                            } else {
                                val msg = when (status) {
                                    401, 403 -> "Bunday oila kodi topilmadi. Kodni tekshirib qayta kiriting."
                                    404 -> "Bunday oila kodi topilmadi. Kodni tekshirib qayta kiriting."
                                    else -> "Oila kodiga ulashda xato ($status)" + if (responseText.isNotBlank()) ": $responseText" else ""
                                }
                                onResult(false, msg)
                            }
                        }
                    } catch (e: Exception) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onResult(false, e.message ?: "Internet aloqasida xato")
                        }
                    } finally {
                        connection?.disconnect()
                    }
                }
            }
            .addOnFailureListener { e ->
                onResult(false, e.message ?: "Firebase avtorizatsiyasida xato")
            }
    }

    private fun childCollection(sub: String): com.google.firebase.firestore.CollectionReference? {
        val code = familyCode ?: return null
        val cid = childId ?: return null
        return db.collection("families").document(code)
            .collection("children").document(cid)
            .collection(sub)
    }

    private fun childDocument(): com.google.firebase.firestore.DocumentReference? {
        val code = familyCode ?: return null
        val cid = childId ?: return null
        return db.collection("families").document(code).collection("children").document(cid)
    }

    fun listenSimInfo(onChange: (count: Int, cards: List<Map<String, Any?>>) -> Unit): com.google.firebase.firestore.ListenerRegistration? {
        val doc = childDocument() ?: run { onChange(0, emptyList()); return null }
        return doc.addSnapshotListener { snap, _ ->
            val data = snap?.data
            val count = (data?.get("simCount") as? Long)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val cards = (data?.get("simCards") as? List<Map<String, Any?>>) ?: emptyList()
            onChange(count, cards)
        }
    }

    fun logCall(event: CallEvent) { childCollection("calls")?.add(event) }
    fun logSms(event: SmsEvent) { childCollection("sms")?.add(event) }
    fun logAppUsage(event: AppUsageEvent) { childCollection("app_usage")?.add(event) }
    fun logLocation(event: LocationEvent) { childCollection("locations")?.add(event) }

    fun fetchChildren(code: String, onResult: (List<Pair<String, String>>) -> Unit) {
        db.collection("families").document(code).collection("children").get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.map { doc ->
                    val name = doc.getString("nomi")?.takeIf { it.isNotBlank() } ?: "Farzand (${doc.id.take(5)})"
                    doc.id to name
                })
            }.addOnFailureListener { onResult(emptyList()) }
    }

    fun unlinkChild(code: String, childId: String, onResult: (Boolean) -> Unit) {
        db.collection("families").document(code).collection("children").document(childId).delete()
            .addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
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
        col.whereGreaterThanOrEqualTo("boshlanishMs", dayStartMs).whereLessThan("boshlanishMs", dayEndMs)
            .orderBy("boshlanishMs", Query.Direction.DESCENDING).addSnapshotListener { snap, _ ->
                onChange(snap?.documents?.mapNotNull { it.toObject(CallEvent::class.java) } ?: emptyList())
            }
    }

    fun listenSmsForDay(dayStartMs: Long, dayEndMs: Long, onChange: (List<SmsEvent>) -> Unit) {
        val col = childCollection("sms") ?: return onChange(emptyList())
        col.whereGreaterThanOrEqualTo("vaqtMs", dayStartMs).whereLessThan("vaqtMs", dayEndMs)
            .orderBy("vaqtMs", Query.Direction.DESCENDING).addSnapshotListener { snap, _ ->
                onChange(snap?.documents?.mapNotNull { it.toObject(SmsEvent::class.java) } ?: emptyList())
            }
    }

    fun listenAppUsageForDay(dayStartMs: Long, dayEndMs: Long, onChange: (List<AppUsageEvent>) -> Unit) {
        val col = childCollection("app_usage") ?: return onChange(emptyList())
        col.whereGreaterThanOrEqualTo("boshlanishMs", dayStartMs).whereLessThan("boshlanishMs", dayEndMs)
            .orderBy("boshlanishMs", Query.Direction.DESCENDING).addSnapshotListener { snap, _ ->
                onChange(snap?.documents?.mapNotNull { it.toObject(AppUsageEvent::class.java) } ?: emptyList())
            }
    }

    fun requestLiveTracking(durationMinutes: Int = 5) {
        val code = familyCode ?: return
        val cid = childId ?: return
        db.collection("families").document(code).collection("children").document(cid)
            .set(mapOf("liveTrackingUntilMs" to System.currentTimeMillis() + durationMinutes * 60_000L), SetOptions.merge())
    }

    fun stopLiveTracking() {
        val code = familyCode ?: return
        val cid = childId ?: return
        db.collection("families").document(code).collection("children").document(cid)
            .set(mapOf("liveTrackingUntilMs" to 0L), SetOptions.merge())
    }

    fun listenLiveTrackingFlag(onChange: (Long) -> Unit): com.google.firebase.firestore.ListenerRegistration? {
        val code = familyCode ?: return null
        val cid = childId ?: return null
        return db.collection("families").document(code).collection("children").document(cid)
            .addSnapshotListener { snap, _ -> onChange(snap?.getLong("liveTrackingUntilMs") ?: 0L) }
    }

    fun listenLatestLocation(onChange: (LocationEvent?) -> Unit): com.google.firebase.firestore.ListenerRegistration? {
        val col = childCollection("locations") ?: run { onChange(null); return null }
        return col.orderBy("vaqtMs", Query.Direction.DESCENDING).limit(1).addSnapshotListener { snap, _ ->
            onChange(snap?.documents?.firstOrNull()?.toObject(LocationEvent::class.java))
        }
    }

    fun fetchLocationsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<LocationEvent>) -> Unit) {
        val col = childCollection("locations") ?: return onResult(emptyList())
        col.whereGreaterThanOrEqualTo("vaqtMs", rangeStartMs).whereLessThan("vaqtMs", rangeEndMs)
            .orderBy("vaqtMs", Query.Direction.ASCENDING).get()
            .addOnSuccessListener { snap -> onResult(snap.documents.mapNotNull { it.toObject(LocationEvent::class.java) }) }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun listenSavedContacts(onChange: (List<ContactMapping>) -> Unit) {
        val col = childCollection("contacts") ?: return onChange(emptyList())
        col.addSnapshotListener { snap, _ -> onChange(snap?.documents?.mapNotNull { it.toObject(ContactMapping::class.java) } ?: emptyList()) }
    }

    fun fetchCallsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<CallEvent>) -> Unit) {
        val col = childCollection("calls") ?: return onResult(emptyList())
        col.whereGreaterThanOrEqualTo("boshlanishMs", rangeStartMs).whereLessThan("boshlanishMs", rangeEndMs).get()
            .addOnSuccessListener { snap -> onResult(snap.documents.mapNotNull { it.toObject(CallEvent::class.java) }) }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun fetchSmsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<SmsEvent>) -> Unit) {
        val col = childCollection("sms") ?: return onResult(emptyList())
        col.whereGreaterThanOrEqualTo("vaqtMs", rangeStartMs).whereLessThan("vaqtMs", rangeEndMs).get()
            .addOnSuccessListener { snap -> onResult(snap.documents.mapNotNull { it.toObject(SmsEvent::class.java) }) }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun fetchAppUsageInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<AppUsageEvent>) -> Unit) {
        val col = childCollection("app_usage") ?: return onResult(emptyList())
        col.whereGreaterThanOrEqualTo("boshlanishMs", rangeStartMs).whereLessThan("boshlanishMs", rangeEndMs).get()
            .addOnSuccessListener { snap -> onResult(snap.documents.mapNotNull { it.toObject(AppUsageEvent::class.java) }) }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun logNotification(event: NotificationEvent) { childCollection("notifications")?.add(event) }

    fun fetchNotificationsInRange(rangeStartMs: Long, rangeEndMs: Long, onResult: (List<NotificationEvent>) -> Unit) {
        val col = childCollection("notifications") ?: return onResult(emptyList())
        col.whereGreaterThanOrEqualTo("vaqtMs", rangeStartMs).whereLessThan("vaqtMs", rangeEndMs).get()
            .addOnSuccessListener { snap -> onResult(snap.documents.mapNotNull { it.toObject(NotificationEvent::class.java) }) }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun checkIsPremium(onResult: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onResult(false)
        db.collection("parents").document(uid).get()
            .addOnSuccessListener { doc -> onResult(doc.getBoolean("premium") == true) }
            .addOnFailureListener { onResult(false) }
    }

    fun sendSupportMessage(matn: String, aloqaRaqami: String, onResult: (Boolean) -> Unit) {
        val user = auth.currentUser ?: return onResult(false)
        val msg = SupportMessage(user.uid, user.email.orEmpty(), matn, aloqaRaqami, System.currentTimeMillis())
        db.collection("support_messages").add(msg).addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    fun listenMySupportMessages(onChange: (List<Pair<String, SupportMessage>>) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onChange(emptyList())
        db.collection("support_messages").whereEqualTo("fromUid", uid).addSnapshotListener { snap, _ ->
            onChange(snap?.documents?.mapNotNull { doc -> doc.toObject(SupportMessage::class.java)?.let { doc.id to it } }?.sortedByDescending { it.second.createdAtMs } ?: emptyList())
        }
    }

    fun listenAllSupportMessages(onChange: (List<Pair<String, SupportMessage>>) -> Unit) {
        db.collection("support_messages").addSnapshotListener { snap, _ ->
            onChange(snap?.documents?.mapNotNull { doc -> doc.toObject(SupportMessage::class.java)?.let { doc.id to it } }?.sortedByDescending { it.second.createdAtMs } ?: emptyList())
        }
    }

    fun replyToSupportMessage(msgId: String, javob: String, onResult: (Boolean) -> Unit) {
        db.collection("support_messages").document(msgId).update(mapOf("adminJavobi" to javob, "holati" to "javob_berildi", "javobVaqtiMs" to System.currentTimeMillis()))
            .addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    fun sendPremiumRequest(izoh: String, skrinshotBase64: String, onResult: (Boolean) -> Unit) {
        val user = auth.currentUser ?: return onResult(false)
        val req = PremiumRequest(user.uid, user.email.orEmpty(), izoh, skrinshotBase64, System.currentTimeMillis())
        db.collection("premium_requests").add(req).addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    fun listenAllPremiumRequests(onChange: (List<Pair<String, PremiumRequest>>) -> Unit) {
        db.collection("premium_requests").addSnapshotListener { snap, _ ->
            onChange(snap?.documents?.mapNotNull { doc -> doc.toObject(PremiumRequest::class.java)?.let { doc.id to it } }?.sortedByDescending { it.second.createdAtMs } ?: emptyList())
        }
    }

    fun approvePremiumRequest(reqId: String, fromUid: String, onResult: (Boolean) -> Unit) {
        val batch = db.batch()
        batch.update(db.collection("premium_requests").document(reqId), mapOf("holati" to "tolandi", "halQilinganMs" to System.currentTimeMillis()))
        batch.set(db.collection("parents").document(fromUid), mapOf("premium" to true), SetOptions.merge())
        batch.commit().addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    fun rejectPremiumRequest(reqId: String, onResult: (Boolean) -> Unit) {
        db.collection("premium_requests").document(reqId).update(mapOf("holati" to "rad_etildi", "halQilinganMs" to System.currentTimeMillis()))
            .addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    fun listenAdminCards(onChange: (List<AdminCard>) -> Unit) {
        db.collection("admin_cards").addSnapshotListener { snap, _ ->
            onChange(snap?.documents?.mapNotNull { doc -> doc.toObject(AdminCard::class.java)?.copy(id = doc.id) } ?: emptyList())
        }
    }

    fun addAdminCard(turi: String, raqam: String, egasi: String, onResult: (Boolean) -> Unit) {
        db.collection("admin_cards").add(AdminCard(turi = turi, raqam = raqam, egasi = egasi))
            .addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    fun deleteAdminCard(cardId: String) { db.collection("admin_cards").document(cardId).delete() }
}
