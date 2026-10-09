package uz.oilanazorati.parentcontrol.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.PremiumParent
import uz.oilanazorati.parentcontrol.model.PremiumRequest
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.AdminConfig

/**
 * Admin panel — FAQAT [AdminConfig.ADMIN_EMAIL] bilan kirilgan
 * hisobga ko'rinadi (MainActivity darajasida tekshiriladi). Uchta
 * bo'lim: Xabarlar (support), Premium so'rovlari, Kartalar.
 *
 * MUHIM: bu yerdagi UI darajasidagi tekshiruv qulaylik uchun — haqiqiy
 * himoya Firestore qoidalaridagi `isAdmin()` orqali ta'minlanadi, shu
 * sabab boshqa hisob bu ekranni ochsa ham hech qanday ma'lumotni
 * o'qiy/o'zgartira olmaydi.
 */
class AdminPanelActivity : AppCompatActivity() {

    private lateinit var messagesList: RecyclerView
    private lateinit var premiumList: RecyclerView
    private lateinit var premiumUsersSection: LinearLayout
    private lateinit var premiumUsersList: RecyclerView
    private lateinit var cardsList: RecyclerView
    private lateinit var cardsSection: LinearLayout
    private lateinit var tabMessages: Button
    private lateinit var tabPremium: Button
    private lateinit var tabPremiumUsers: Button
    private lateinit var tabCards: Button

    // Faqat EGASI (asosiy admin) premium tarixini boshqa adminlardan yashira/ko'rsata oladi.
    // Yashirilgan foydalanuvchilar premiumda qoladi, ma'lumot o'chirilmaydi; faqat boshqa
    // adminlarning ro'yxatida (premium foydalanuvchilar va to'lov so'rovlari) ko'rinmaydi.
    private var isOwner = false
    private var rawUsers: List<PremiumParent> = emptyList()
    private var rawRequests: List<Pair<String, PremiumRequest>> = emptyList()

    private val messagesAdapter = AdminSupportMessageAdapter { docId, msg -> showReplyDialog(docId, msg.adminJavobi) }
    private val premiumAdapter = AdminPremiumRequestAdapter(
        onApprove = { docId, req ->
            FirebaseRepo.approvePremiumRequest(docId, req.fromUid) { success ->
                val msg = if (success) "Premium berildi" else "Xato yuz berdi"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        },
        onReject = { docId ->
            FirebaseRepo.rejectPremiumRequest(docId) { }
        },
        onToggleHide = { docId, req -> toggleRequestHidden(docId, req) }
    )
    private val premiumUsersAdapter = AdminPremiumUserAdapter(onRevoke = { user ->
        val nomi = user.ismi.ifBlank { user.email.ifBlank { user.uid } }
        AlertDialog.Builder(this)
            .setTitle("Premiumni bekor qilish")
            .setMessage("\"$nomi\" uchun premium huquqi bekor qilinsinmi?")
            .setPositiveButton("Ha, bekor qilish") { _, _ ->
                FirebaseRepo.revokePremium(user.uid) { success ->
                    val msg = if (success) "Premium bekor qilindi" else "Xato yuz berdi"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Yo'q", null)
            .show()
    }, onToggleHide = { user -> toggleUserHidden(user) })
    private val cardsAdapter = AdminCardAdapter { card ->
        AlertDialog.Builder(this)
            .setTitle("Kartani o'chirish")
            .setMessage("${card.turi} — ${card.raqam} o'chirilsinmi?")
            .setPositiveButton("O'chirish") { _, _ -> FirebaseRepo.deleteAdminCard(card.id) }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AdminConfig.checkCurrentUserAdmin { isAdmin ->
            if (!isAdmin) {
                Toast.makeText(this, "Sizda admin huquqi yo'q", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                initAdminPanel()
            }
        }
    }

    private fun initAdminPanel() {
        setContentView(R.layout.activity_admin_panel)

        messagesList = findViewById(R.id.messagesList)
        premiumList = findViewById(R.id.premiumList)
        premiumUsersSection = findViewById(R.id.premiumUsersSection)
        premiumUsersList = findViewById(R.id.premiumUsersList)
        cardsList = findViewById(R.id.cardsList)
        cardsSection = findViewById(R.id.cardsSection)
        tabMessages = findViewById(R.id.tabMessages)
        tabPremium = findViewById(R.id.tabPremium)
        tabPremiumUsers = findViewById(R.id.tabPremiumUsers)
        tabCards = findViewById(R.id.tabCards)

        messagesList.layoutManager = LinearLayoutManager(this)
        messagesList.adapter = messagesAdapter
        premiumList.layoutManager = LinearLayoutManager(this)
        premiumList.adapter = premiumAdapter
        premiumUsersList.layoutManager = LinearLayoutManager(this)
        premiumUsersList.adapter = premiumUsersAdapter
        cardsList.layoutManager = LinearLayoutManager(this)
        cardsList.adapter = cardsAdapter

        tabMessages.setOnClickListener { showTab(0) }
        tabPremium.setOnClickListener { showTab(1) }
        tabPremiumUsers.setOnClickListener { showTab(2) }
        tabCards.setOnClickListener { showTab(3) }

        findViewById<View>(R.id.btnAddCard).setOnClickListener { showAddCardDialog() }

        FirebaseRepo.listenAllSupportMessages { list -> messagesAdapter.setData(list) }
        isOwner = AdminConfig.isOwner()
        FirebaseRepo.listenAllPremiumRequests { list -> rawRequests = list; refreshPremiumLists() }
        FirebaseRepo.listenPremiumParents { list -> rawUsers = list; refreshPremiumLists() }
        FirebaseRepo.listenAdminCards { list -> cardsAdapter.setData(list) }

        showTab(0)
    }

    /** Egasi hammasini (yashirilganlari belgilangan holda) ko'radi; boshqa adminlar — yashirilmaganlarni. */
    private fun refreshPremiumLists() {
        val hiddenUids = rawUsers.filter { it.adminHidden }.map { it.uid }.toSet()
        premiumUsersAdapter.isOwner = isOwner
        premiumAdapter.isOwner = isOwner
        premiumAdapter.hiddenUids = hiddenUids
        premiumUsersAdapter.setData(if (isOwner) rawUsers else rawUsers.filterNot { it.adminHidden })
        premiumAdapter.setData(
            if (isOwner) rawRequests
            else rawRequests.filterNot { it.second.adminHidden || it.second.fromUid in hiddenUids }
        )
    }

    private fun toggleUserHidden(user: PremiumParent) {
        if (!isOwner) return
        val nomi = user.ismi.ifBlank { user.email.ifBlank { user.uid } }
        val hide = !user.adminHidden
        AlertDialog.Builder(this)
            .setTitle(if (hide) "Boshqa adminlardan yashirish" else "Qayta ko'rsatish")
            .setMessage(
                if (hide) "\"$nomi\" va uning to'lov tarixi boshqa adminlarga ko'rinmaydi. Foydalanuvchi premiumda QOLADI, hech narsa o'chirilmaydi — faqat siz ko'rasiz."
                else "\"$nomi\" va uning to'lov tarixi boshqa adminlarga yana ko'rinadi."
            )
            .setPositiveButton("Ha") { _, _ ->
                FirebaseRepo.setPremiumUserHidden(user.uid, hide) { ok ->
                    Toast.makeText(this, if (ok) (if (hide) "Boshqa adminlardan yashirildi" else "Qayta ko'rsatildi") else "Xato yuz berdi", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Yo'q", null)
            .show()
    }

    private fun toggleRequestHidden(docId: String, req: PremiumRequest) {
        if (!isOwner) return
        val userHidden = rawUsers.any { it.uid == req.fromUid && it.adminHidden }
        val hiddenNow = req.adminHidden || userHidden
        val message = when {
            !hiddenNow -> "Bu to'lov so'rovi boshqa adminlarga ko'rinmaydi. Hech narsa o'chirilmaydi, faqat siz ko'rasiz."
            userHidden -> "Bu foydalanuvchi yashirilgan: qayta ko'rsatsangiz, uning BARCHA so'rovlari boshqa adminlarga ko'rinadi."
            else -> "Bu to'lov so'rovi boshqa adminlarga yana ko'rinadi."
        }
        AlertDialog.Builder(this)
            .setTitle(if (hiddenNow) "Qayta ko'rsatish" else "Boshqa adminlardan yashirish")
            .setMessage(message)
            .setPositiveButton("Ha") { _, _ ->
                val done: (Boolean) -> Unit = { ok ->
                    Toast.makeText(this, if (ok) "Bajarildi" else "Xato yuz berdi", Toast.LENGTH_SHORT).show()
                }
                when {
                    userHidden -> FirebaseRepo.setPremiumUserHidden(req.fromUid, false, done)
                    else -> FirebaseRepo.setPremiumRequestHidden(docId, !req.adminHidden, done)
                }
            }
            .setNegativeButton("Yo'q", null)
            .show()
    }

    private fun showTab(index: Int) {
        messagesList.visibility = if (index == 0) View.VISIBLE else View.GONE
        premiumList.visibility = if (index == 1) View.VISIBLE else View.GONE
        premiumUsersSection.visibility = if (index == 2) View.VISIBLE else View.GONE
        cardsSection.visibility = if (index == 3) View.VISIBLE else View.GONE

        val activeBg = R.drawable.bg_button_primary
        val inactiveBg = R.drawable.bg_card_clickable
        tabMessages.setBackgroundResource(if (index == 0) activeBg else inactiveBg)
        tabPremium.setBackgroundResource(if (index == 1) activeBg else inactiveBg)
        tabPremiumUsers.setBackgroundResource(if (index == 2) activeBg else inactiveBg)
        tabCards.setBackgroundResource(if (index == 3) activeBg else inactiveBg)
        tabMessages.setTextColor(if (index == 0) 0xFF0D1117.toInt() else 0xFFE6E9EF.toInt())
        tabPremium.setTextColor(if (index == 1) 0xFF0D1117.toInt() else 0xFFE6E9EF.toInt())
        tabPremiumUsers.setTextColor(if (index == 2) 0xFF0D1117.toInt() else 0xFFE6E9EF.toInt())
        tabCards.setTextColor(if (index == 3) 0xFF0D1117.toInt() else 0xFFE6E9EF.toInt())
    }

    private fun showReplyDialog(docId: String, existingReply: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(existingReply)
            hint = "Javob matni"
        }
        AlertDialog.Builder(this)
            .setTitle("Javob yozish")
            .setView(input)
            .setPositiveButton("Yuborish") { _, _ ->
                val reply = input.text?.toString()?.trim().orEmpty()
                if (reply.isNotBlank()) {
                    FirebaseRepo.replyToSupportMessage(docId, reply) { }
                }
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun showAddCardDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val typeInput = EditText(this).apply { hint = "Turi (masalan UZCARD, HUMO)" }
        val numberInput = EditText(this).apply {
            hint = "Karta raqami"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val holderInput = EditText(this).apply { hint = "Karta egasi (F.I.Sh.)" }
        container.addView(typeInput)
        container.addView(numberInput)
        container.addView(holderInput)

        AlertDialog.Builder(this)
            .setTitle("Yangi karta qo'shish")
            .setView(container)
            .setPositiveButton("Qo'shish") { _, _ ->
                val turi = typeInput.text?.toString()?.trim().orEmpty()
                val raqam = numberInput.text?.toString()?.trim().orEmpty()
                val egasi = holderInput.text?.toString()?.trim().orEmpty()
                if (turi.isBlank() || raqam.isBlank()) {
                    Toast.makeText(this, "Turi va raqamni kiriting", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                FirebaseRepo.addAdminCard(turi, raqam, egasi) { }
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }
}
