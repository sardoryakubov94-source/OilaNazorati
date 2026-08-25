package uz.oilanazorati.parentcontrol.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * "Bizga yozing" ekrani — foydalanuvchi xabar va (ixtiyoriy) bog'lanish
 * uchun raqamini yuboradi, admin panelidan javob kelganda shu yerda
 * ko'rinadi. Ishonch telefoni yoki qo'ng'iroq qilish tugmasi ATAYLAB
 * yo'q — faqat yozma xabar orqali murojaat.
 */
class SupportActivity : AppCompatActivity() {

    private lateinit var inputMessage: EditText
    private lateinit var inputContactNumber: EditText
    private lateinit var messageList: RecyclerView
    private lateinit var emptyStateText: View
    private val adapter = SupportMessageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)

        inputMessage = findViewById(R.id.inputMessage)
        inputContactNumber = findViewById(R.id.inputContactNumber)
        messageList = findViewById(R.id.messageList)
        emptyStateText = findViewById(R.id.emptyStateText)

        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter

        findViewById<View>(R.id.btnSendMessage).setOnClickListener { sendMessage() }

        FirebaseRepo.listenMySupportMessages { list ->
            adapter.setData(list)
            emptyStateText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun sendMessage() {
        val text = inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Xabar matnini kiriting", Toast.LENGTH_SHORT).show()
            return
        }
        val contact = inputContactNumber.text?.toString()?.trim().orEmpty()
        FirebaseRepo.sendSupportMessage(text, contact) { success ->
            if (success) {
                Toast.makeText(this, "Xabar yuborildi", Toast.LENGTH_SHORT).show()
                inputMessage.setText("")
                inputContactNumber.setText("")
            } else {
                Toast.makeText(this, "Xato yuz berdi, qayta urinib ko'ring", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
