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
    private lateinit var cardsList: RecyclerView
    private lateinit var cardsSection: LinearLayout
    private lateinit var tabMessages: Button
    private lateinit var tabPremium: Button
    private lateinit var tabCards: Button

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
        }
    )
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

        if (!AdminConfig.isCurrentUserAdmin()) {
            Toast.makeText(this, "Sizda admin huquqi yo'q", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(R.layout.activity_admin_panel)

        messagesList = findViewById(R.id.messagesList)
        premiumList = findViewById(R.id.premiumList)
        cardsList = findViewById(R.id.cardsList)
        cardsSection = findViewById(R.id.cardsSection)
        tabMessages = findViewById(R.id.tabMessages)
        tabPremium = findViewById(R.id.tabPremium)
        tabCards = findViewById(R.id.tabCards)

        messagesList.layoutManager = LinearLayoutManager(this)
        messagesList.adapter = messagesAdapter
        premiumList.layoutManager = LinearLayoutManager(this)
        premiumList.adapter = premiumAdapter
        cardsList.layoutManager = LinearLayoutManager(this)
        cardsList.adapter = cardsAdapter

        tabMessages.setOnClickListener { showTab(0) }
        tabPremium.setOnClickListener { showTab(1) }
        tabCards.setOnClickListener { showTab(2) }

        findViewById<View>(R.id.btnAddCard).setOnClickListener { showAddCardDialog() }

        FirebaseRepo.listenAllSupportMessages { list -> messagesAdapter.setData(list) }
        FirebaseRepo.listenAllPremiumRequests { list -> premiumAdapter.setData(list) }
        FirebaseRepo.listenAdminCards { list -> cardsAdapter.setData(list) }

        showTab(0)
    }

    private fun showTab(index: Int) {
        messagesList.visibility = if (index == 0) View.VISIBLE else View.GONE
        premiumList.visibility = if (index == 1) View.VISIBLE else View.GONE
        cardsSection.visibility = if (index == 2) View.VISIBLE else View.GONE

        val activeBg = R.drawable.bg_button_primary
        val inactiveBg = R.drawable.bg_card_clickable
        tabMessages.setBackgroundResource(if (index == 0) activeBg else inactiveBg)
        tabPremium.setBackgroundResource(if (index == 1) activeBg else inactiveBg)
        tabCards.setBackgroundResource(if (index == 2) activeBg else inactiveBg)
        tabMessages.setTextColor(if (index == 0) 0xFF0D1117.toInt() else 0xFFE6E9EF.toInt())
        tabPremium.setTextColor(if (index == 1) 0xFF0D1117.toInt() else 0xFFE6E9EF.toInt())
        tabCards.setTextColor(if (index == 2) 0xFF0D1117.toInt() else 0xFFE6E9EF.toInt())
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
