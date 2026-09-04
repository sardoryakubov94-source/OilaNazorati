package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.AdminConfig

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindBottomNav(NavTab.SETTINGS)
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        val currentCode = FirebaseRepo.familyCode ?: getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getString("family_code", null)
        if (currentCode != null) findViewById<TextView>(R.id.currentFamilyCodeSubtitle).text = "Joriy kod: $currentCode"
        val isLight = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getBoolean("light_theme", false)
        findViewById<TextView>(R.id.currentThemeSubtitle).text = if (isLight) "Kunduzgi rejim" else "Tungi rejim (standart)"
        findViewById<android.view.View>(R.id.rowEnterCode).setOnClickListener { showEnterCodeDialog() }
        findViewById<android.view.View>(R.id.rowNewCode).setOnClickListener { createNewFamilyCode() }
        findViewById<android.view.View>(R.id.rowTheme).setOnClickListener { showThemePickerDialog() }
        findViewById<android.view.View>(R.id.premiumBanner).setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }
        findViewById<android.view.View>(R.id.btnPremiumCta).setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }

        val outer = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? android.view.ViewGroup
        val target = if (outer is ScrollView && outer.childCount > 0) outer.getChildAt(0) as? android.view.ViewGroup else outer
        target?.addView(Button(this).apply {
            text = "📸 Screenshot nazorati"
            setOnClickListener {
                if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) Toast.makeText(this@SettingsActivity, "Avval farzandni tanlang", Toast.LENGTH_SHORT).show()
                else startActivity(Intent(this@SettingsActivity, ScreenshotSettingsActivity::class.java))
            }
        })

        if (AdminConfig.isCurrentUserAdmin()) {
            findViewById<android.view.View>(R.id.adminSection).visibility = android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.rowAdminPanel).setOnClickListener { startActivity(Intent(this, AdminPanelActivity::class.java)) }
        }
    }
    private fun showThemePickerDialog() { val options=arrayOf("Tungi rejim (standart)","Kunduzgi rejim"); val current=getSharedPreferences("oila_nazorati",Context.MODE_PRIVATE).getBoolean("light_theme",false); AlertDialog.Builder(this).setTitle("Mavzu").setSingleChoiceItems(options,if(current)1 else 0){dialog,which->val light=which==1;getSharedPreferences("oila_nazorati",Context.MODE_PRIVATE).edit().putBoolean("light_theme",light).apply();AppCompatDelegate.setDefaultNightMode(if(light)AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES);dialog.dismiss()}.setNegativeButton("Bekor qilish",null).show() }
    private fun showEnterCodeDialog(){val input=EditText(this).apply{hint="Oila kodi";setText(FirebaseRepo.familyCode.orEmpty())};val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;val pad=(16*resources.displayMetrics.density).toInt();setPadding(pad,pad,pad,0);addView(input)};AlertDialog.Builder(this).setTitle("Oila kodi").setView(container).setPositiveButton("Yuklash"){_,_->input.text?.toString()?.trim()?.uppercase()?.takeIf{it.isNotBlank()}?.let{saveFamilyCodeAndReturn(it)}}.setNegativeButton("Bekor qilish",null).show()}
    private fun createNewFamilyCode(){AlertDialog.Builder(this).setTitle("Yangi oila kodi yaratish").setMessage("Bu yangi, hozirgi kodga bog'liq bo'lmagan oila yaratadi. Shu oilaga yana farzand qo'shish uchun yangi kod yaratmang.").setPositiveButton("Baribir yarataman"){_,_->generateAndCreateFamily()}.setNegativeButton("Bekor qilish",null).show()}
    private fun generateAndCreateFamily(){val code=generateFamilyCode();FirebaseRepo.createFamily(code){ok,err->if(ok)showGeneratedCodeDialog(code)else Toast.makeText(this,"Kod yaratishda xato: ${err?:"noma'lum"}",Toast.LENGTH_LONG).show()}}
    private fun generateFamilyCode():String{val chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";return(1..6).map{chars.random()}.joinToString("")}
    private fun showGeneratedCodeDialog(code:String){AlertDialog.Builder(this).setTitle("Yangi oila kodi yaratildi").setMessage("$code\n\nBu kodni bola qurilmasiga kiriting.").setPositiveButton("Tushunarli"){_,_->saveFamilyCodeAndReturn(code)}.setCancelable(false).show()}
    private fun saveFamilyCodeAndReturn(code:String){FirebaseRepo.familyCode=code;FirebaseRepo.childId=null;getSharedPreferences("oila_nazorati",Context.MODE_PRIVATE).edit().putString("family_code",code).remove("child_id").apply();startActivity(Intent(this,ParentDashboardActivity::class.java));finish()}
    @Suppress("DEPRECATION") override fun onBackPressed(){moveTaskToBack(true)}
}
