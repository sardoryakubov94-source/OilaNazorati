package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.databinding.ActivityMainBinding
import uz.oilanazorati.parentcontrol.util.AppPasswordUtil

/**
 * Ilovaning kirish nuqtasi. Uch bosqichli oqim:
 *
 * 1) UMUMIY PAROL (passwordSection) — bank ilovalaridagi kabi, ilova
 *    HAR safar sovuq holatda ochilganda birinchi shu ekran ko'rinadi.
 *    Bu yerda Google UMUMAN ishtirok etmaydi — faqat oddiy parol.
 *    Bola telefonida FAQAT shu bitta parol turadi (avval
 *    ChildSetupActivity'da alohida PIN bor edi — u olib tashlandi,
 *    chunki endi shu umumiy parol uni almashtiradi).
 *
 * 2) ROL TANLASH (roleSection) — parol to'g'ri kiritilgandan keyin.
 *    "Bu farzandimning telefoni" to'g'ridan-to'g'ri o'tadi (Google
 *    kerak emas). "Ota-ona panelini ochish" esa 3-bosqichga o'tkazadi.
 *
 * 3) GOOGLE KIRISH (loginSection) — FAQAT Ota-ona panelini ochish
 *    uchun kerak, chunki Firestore qoidalari ma'lumotni faqat kodni
 *    yaratgan Google hisobiga ko'rsatadi (xavfsizlik uchun).
 */
class MainActivity : AppCompatActivity() {

    private enum class PendingDestination { PARENT_DASHBOARD, SUPPORT }
    private var pendingDestination = PendingDestination.PARENT_DASHBOARD

    private lateinit var binding: ActivityMainBinding
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account.idToken)
        } catch (e: ApiException) {
            binding.loginStatusText.text = "Xato kodi: " + e.statusCode + " - " + e.message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ota-ona paneli uchun Google talabi hamon "har safar qayta
        // kirish" tartibida qoladi — faqat anonim (bola) sessiyaga
        // tegilmaydi, uni signOut qilmaymiz.
        val cachedUser = FirebaseAuth.getInstance().currentUser
        if (cachedUser != null && !cachedUser.isAnonymous) {
            FirebaseAuth.getInstance().signOut()
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        binding.btnPasswordSubmit.setOnClickListener { onPasswordSubmit() }
        binding.btnChildDevice.setOnClickListener {
            startActivity(Intent(this, ChildSetupActivity::class.java))
        }
        binding.btnOpenParentDashboard.setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser?.isAnonymous == false) {
                startActivity(Intent(this, ParentDashboardActivity::class.java))
            } else {
                pendingDestination = PendingDestination.PARENT_DASHBOARD
                showGoogleLoginSection()
            }
        }
        binding.btnSupport.setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser?.isAnonymous == false) {
                startActivity(Intent(this, SupportActivity::class.java))
            } else {
                pendingDestination = PendingDestination.SUPPORT
                showGoogleLoginSection()
            }
        }
        binding.btnGoogleSignIn.setOnClickListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
        binding.btnBackFromLogin.setOnClickListener { showRoleSection() }

        showPasswordSection()
    }

    // ---------------- 1-bosqich: umumiy parol ----------------

    private fun showPasswordSection() {
        binding.passwordSection.visibility = View.VISIBLE
        binding.roleSection.visibility = View.GONE
        binding.loginSection.visibility = View.GONE

        val prefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        val hasPassword = prefs.contains("app_password_hash")
        if (hasPassword) {
            // Keyingi kirishlarda hech qanday izoh yozuv ko'rsatilmaydi —
            // faqat tepada bulut belgisi va pastda parol maydoni.
            binding.passwordPromptText.visibility = View.GONE
        } else {
            binding.passwordPromptText.visibility = View.VISIBLE
            binding.passwordPromptText.text = "Ilova uchun parol o'rnating (kamida 4 ta belgi)"
        }
        binding.inputAppPassword.setText("")
        binding.passwordStatusText.text = ""
    }

    private fun onPasswordSubmit() {
        val prefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        val savedHash = prefs.getString("app_password_hash", null)
        val entered = binding.inputAppPassword.text?.toString().orEmpty()

        if (savedHash == null) {
            // Birinchi marta — parol o'rnatilmoqda
            if (entered.length < 4) {
                binding.passwordStatusText.text = "Kamida 4 ta belgi kiriting"
                return
            }
            prefs.edit().putString("app_password_hash", AppPasswordUtil.hash(entered)).apply()
            showRoleSection()
        } else {
            if (AppPasswordUtil.hash(entered) == savedHash) {
                showRoleSection()
            } else {
                binding.passwordStatusText.text = "Parol noto'g'ri"
                binding.inputAppPassword.setText("")
            }
        }
    }

    // ---------------- 2-bosqich: rol tanlash ----------------

    private fun showRoleSection() {
        binding.passwordSection.visibility = View.GONE
        binding.roleSection.visibility = View.VISIBLE
        binding.loginSection.visibility = View.GONE
    }

    // ---------------- 3-bosqich: faqat Ota-ona paneli uchun Google ----------------

    private fun showGoogleLoginSection() {
        binding.passwordSection.visibility = View.GONE
        binding.roleSection.visibility = View.GONE
        binding.loginSection.visibility = View.VISIBLE
        binding.loginStatusText.text = ""
    }

    private fun firebaseAuthWithGoogle(idToken: String?) {
        if (idToken == null) return
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user ?: return@addOnSuccessListener
                saveParentProfile(user.uid, user.displayName.orEmpty(), user.email.orEmpty())
                val destination = when (pendingDestination) {
                    PendingDestination.SUPPORT -> SupportActivity::class.java
                    PendingDestination.PARENT_DASHBOARD -> ParentDashboardActivity::class.java
                }
                startActivity(Intent(this, destination))
            }
            .addOnFailureListener {
                binding.loginStatusText.text = "Kirishda xato yuz berdi, qayta urinib ko'ring"
            }
    }

    private fun saveParentProfile(uid: String, name: String, email: String) {
        FirebaseFirestore.getInstance()
            .collection("parents").document(uid)
            .set(
                mapOf(
                    "ismi" to name,
                    "email" to email,
                    "oxirgiKirishMs" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
    }
}
