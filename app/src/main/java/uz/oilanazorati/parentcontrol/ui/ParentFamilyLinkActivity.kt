package uz.oilanazorati.parentcontrol.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.firebase.auth.FirebaseAuth
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.databinding.ActivityParentFamilyLinkBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/** Birinchi Gmail kirishi va keyingi "Bolalarim" tanlovi uchun umumiy to'liq ekran. */
class ParentFamilyLinkActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParentFamilyLinkBinding
    private var childrenListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var familyCode: String = ""
    private var parentUid: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentFamilyLinkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        familyCode = intent.getStringExtra(EXTRA_FAMILY_CODE)
            ?: getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getString("parent_family_code", null).orEmpty()
        parentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

        if (familyCode.isBlank() || parentUid.isBlank() || FirebaseAuth.getInstance().currentUser?.isAnonymous != false) {
            Toast.makeText(this, "Ota-ona Gmail hisobi aniqlanmadi. Qayta kiring.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        FirebaseRepo.familyCode = familyCode
        binding.familyCodeText.text = familyCode
        binding.btnCopyCode.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OilaNazorati oila kodi", familyCode))
            Toast.makeText(this, "Oila kodi nusxalandi", Toast.LENGTH_SHORT).show()
        }
        binding.btnAddChild.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OilaNazorati oila kodi", familyCode))
            Toast.makeText(this, "Oila kodi nusxalandi. Uni boshqa farzandingiz telefoniga yuboring.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (familyCode.isBlank()) return
        childrenListener?.remove()
        childrenListener = FirebaseRepo.listenChildren(familyCode) { children ->
            if (isFinishing || isDestroyed) return@listenChildren
            renderChildren(children)
        }
    }

    private fun renderChildren(children: List<FirebaseRepo.LinkedChildDevice>) {
        binding.childrenContainer.removeAllViews()
        binding.emptyChildrenText.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
        children.forEach { child ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(androidx.core.content.ContextCompat.getColor(this@ParentFamilyLinkActivity, R.color.color_surface))
                    setStroke(dp(1), androidx.core.content.ContextCompat.getColor(this@ParentFamilyLinkActivity, R.color.color_border))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { selectChild(child.id, child.platform) }
            }
            val avatar = TextView(this).apply {
                text = when (ChildDevicePlatform.from(child.platform)) { ChildDevicePlatform.IOS -> ""; ChildDevicePlatform.ANDROID -> "A"; ChildDevicePlatform.UNKNOWN -> "?" }
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentFamilyLinkActivity, R.color.color_primary))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(androidx.core.content.ContextCompat.getColor(this@ParentFamilyLinkActivity, R.color.color_bg))
                }
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
            }
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            labels.addView(TextView(this).apply {
                text = child.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentFamilyLinkActivity, R.color.color_text_primary))
            })
            labels.addView(TextView(this).apply {
                text = when (ChildDevicePlatform.from(child.platform)) {\n                    ChildDevicePlatform.IOS -> "iPhone / iOS qurilmasi"\n                    ChildDevicePlatform.ANDROID -> "Android qurilmasi"\n                    ChildDevicePlatform.UNKNOWN -> "Qurilma turi aniqlanmadi"\n                }
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentFamilyLinkActivity, R.color.color_text_secondary))
                setPadding(0, dp(3), 0, 0)
            })
            val arrow = TextView(this).apply {
                text = "›"
                textSize = 28f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentFamilyLinkActivity, R.color.color_text_secondary))
            }
            row.addView(avatar); row.addView(labels); row.addView(arrow)
            binding.childrenContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun selectChild(childId: String, platform: String) {
        val normalizedPlatform = ChildDevicePlatform.from(platform)
        if (normalizedPlatform == ChildDevicePlatform.UNKNOWN) {
            AlertDialog.Builder(this)
                .setTitle("Qurilma turi aniqlanmadi")
                .setMessage("Bu farzand qurilmasining Android yoki iOS ekanligi haqida ma'lumot topilmadi. Xavfsizlik uchun boshqaruv funksiyalari ochilmadi. Farzand ilovasini yangilab, qayta ulab ko'ring.")
                .setPositiveButton("Tushunarli", null)
                .show()
            return
        }
        val platformValue = if (normalizedPlatform == ChildDevicePlatform.IOS) "ios" else "android"
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("parent_uid", parentUid)
            .putString("parent_family_code", familyCode)
            .putString("parent_selected_child_id", childId)
            .putString("parent_selected_platform", platformValue)
            .putString("family_code", familyCode)
            .putString("child_id", childId)
            .apply()
        FirebaseRepo.familyCode = familyCode
        FirebaseRepo.childId = childId
        startActivity(Intent(this, ParentDashboardActivity::class.java).apply {
            putExtra(ParentDashboardActivity.EXTRA_CHILD_ID, childId)
            putExtra(ParentDashboardActivity.EXTRA_CHILD_PLATFORM, platformValue)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        childrenListener?.remove()
        childrenListener = null
        super.onStop()
    }

    companion object {
        const val EXTRA_FAMILY_CODE = "parent_family_code"
    }
}
