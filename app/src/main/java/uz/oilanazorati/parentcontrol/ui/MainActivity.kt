package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
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

        FirebaseRepo.checkMinRequiredVersion(uz.oilanazorati.parentcontrol.BuildConfig.VERSION_CODE) { mustUpdate, _ ->
            if (mustUpdate) {
                startActivity(Intent(this, UpdateRequiredActivity::class.java))
                finish()
            }
        }

        val onboardingPrefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        if (!onboardingPrefs.getBoolean("onboarding_shown", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Existing child installations may be upgraded directly without
        // visiting ChildSetupActivity again. Start the idle Agora microphone
        // FGS while this Activity is visible; the service still opens the
        // microphone only when an explicit Agora request arrives.
        val childPrefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        if (
            childPrefs.getBoolean("is_child_device", false) &&
            !childPrefs.getString("family_code", null).isNullOrBlank() &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    Intent(this, uz.oilanazorati.parentcontrol.service.AgoraMicService::class.java)
                )
            } catch (_: Throwable) {
                // The Activity is visible here; Android should allow the
                // microphone FGS. If a manufacturer blocks it, setup can
                // retry when the child opens the app again.
            }
        }

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
            // Oldin ulangan bola qurilmasi bo'lsa, ism va oila kodi qayta so'ralmaydi,
            // bola paneli darhol ochiladi (o'zgartirish tugmasi panelning o'zida).
            val savedPrefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            val alreadyLinked = !savedPrefs.getString("family_code", null).isNullOrBlank() &&
                (savedPrefs.contains("child_name") ||
                    savedPrefs.getBoolean("is_child_device", false) ||
                    savedPrefs.getString("child_platform", null) == "android")
            if (alreadyLinked) {
                startActivity(Intent(this, ChildSetupActivity::class.java).apply {
                    putExtra(FamilyCodeEntryActivity.EXTRA_FAMILY_LINKED, true)
                })
                return@setOnClickListener
            }
            startActivity(Intent(this, FamilyCodeEntryActivity::class.java))
        }
        binding.btnOpenParentDashboard.setOnClickListener {
            ParentLegalNoticeGate.showIfNeeded(this) {
                if (FirebaseAuth.getInstance().currentUser?.isAnonymous == false) {
                    startActivity(Intent(this, ParentDashboardActivity::class.java))
                } else {
                    pendingDestination = PendingDestination.PARENT_DASHBOARD
                    showGoogleLoginSection()
                }
            }
        }
        binding.btnSetupGuide.setOnClickListener {
            startActivity(Intent(this, SetupGuideActivity::class.java))
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
        // Parol to'g'ri kiritilib, ichkariga o'tilgach, klaviatura ekranda
        // osilib qolmasligi uchun avtomatik yashiriladi.
        currentFocus?.let { focused ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
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
                if (pendingDestination == PendingDestination.SUPPORT) {
                    startActivity(Intent(this, SupportActivity::class.java))
                    return@addOnSuccessListener
                }

                // Tezkor yo'l: shu Gmail uchun oila kodi qurilmada saqlangan bo'lsa,
                // serverni kutmasdan darhol ochamiz; haqiqiy kod fonda tekshiriladi.
                val fastPrefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
                val cachedFamily = fastPrefs.getString("parent_family_code", null)
                if (fastPrefs.getString("parent_uid", null) == user.uid && !cachedFamily.isNullOrBlank()) {
                    openAfterFamilyKnown(cachedFamily, user.uid)
                    FirebaseRepo.findOrCreateFamilyForCurrentUser { realCode ->
                        if (!realCode.isNullOrBlank() && realCode != cachedFamily) {
                            FirebaseRepo.familyCode = realCode
                            fastPrefs.edit()
                                .putString("parent_family_code", realCode)
                                .putString("family_code", realCode)
                                .apply()
                        }
                    }
                    return@addOnSuccessListener
                }

                // Har bir Gmail kirishida oila kodi serverdan aniqlanadi.
                // Bola oldin tanlangan bo'lsa, aynan shu Gmail + oila uchun saqlangan
                // tanlov tiklanadi va tanlash oynasi qayta ko'rsatilmaydi.
                binding.loginStatusText.text = "Oila ma'lumotlari tekshirilmoqda..."
                var answered = false
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!answered) {
                        answered = true
                        binding.loginStatusText.text = "❌ Internet aloqasi yo'q yoki juda sekin. Qayta urinib ko'ring."
                    }
                }
                timeoutHandler.postDelayed(timeoutRunnable, 15_000L)
                FirebaseRepo.findOrCreateFamilyForCurrentUser { code ->
                    if (answered) return@findOrCreateFamilyForCurrentUser
                    answered = true
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    if (code == null) {
                        binding.loginStatusText.text = "Oila ma'lumotlarini olishda xato. Qayta urinib ko'ring."
                        return@findOrCreateFamilyForCurrentUser
                    }
                    openAfterFamilyKnown(code, user.uid)
                }
            }
            .addOnFailureListener {
                binding.loginStatusText.text = "Kirishda xato yuz berdi, qayta urinib ko'ring"
            }
    }

    private fun openAfterFamilyKnown(code: String, uid: String) {
        FirebaseRepo.familyCode = code
        val prefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        val selectedChild = prefs.getString("parent_selected_child_id", null)
        val sameParent = prefs.getString("parent_uid", null) == uid
        val sameFamily = prefs.getString("parent_family_code", null) == code
        val validSavedSelection = sameParent && sameFamily && !selectedChild.isNullOrBlank()

        prefs.edit()
            .putString("parent_uid", uid)
            .putString("parent_family_code", code)
            .putString("family_code", code)
            .apply()

        if (validSavedSelection) {
            FirebaseRepo.childId = selectedChild
            startActivity(Intent(this, ParentDashboardActivity::class.java).apply {
                putExtra("selected_child_id", selectedChild)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } else {
            startActivity(Intent(this, ParentFamilyLinkActivity::class.java).apply {
                putExtra(ParentFamilyLinkActivity.EXTRA_FAMILY_CODE, code)
            })
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
