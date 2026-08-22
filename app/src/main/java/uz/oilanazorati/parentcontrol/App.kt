package uz.oilanazorati.parentcontrol

import android.app.Application
import android.content.Context
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * Ilova jarayoni (process) har safar yangidan boshlanganda — masalan,
 * qurilma qayta yoqilgandan so'ng BootReceiver orqali MonitorForegroundService
 * ishga tushganda, yoki tizim ilovani fondan tozalab, keyin biror
 * ContentObserver/BroadcastReceiver (masalan SmsReceiver) alohida
 * chaqirilganda — FirebaseRepo.familyCode va FirebaseRepo.childId
 * xotirada (RAM) saqlanadi, shuning uchun yangi jarayonda ular BO'SH
 * (null) bo'ladi.
 *
 * Bu klass ilova jarayoni boshlanishi bilan ENG BIRINCHI bo'lib ishga
 * tushadi (har qanday Activity/Service/Receiver'dan oldin) va saqlangan
 * oila kodi/bola ID'sini SharedPreferences'dan o'qib, FirebaseRepo'ga
 * qaytadan joylashtiradi. Shu tufayli qurilma qayta yoqilgandan keyin ham,
 * yoki tizim ilova jarayonini tozalab qo'yganda ham, kuzatuv (qo'ng'iroq,
 * SMS, joylashuv, ilova ishlatilishi) yozilishda uzilish bo'lmaydi.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        restoreSavedPairing(this)
    }

    companion object {
        fun restoreSavedPairing(context: Context) {
            // Xotirada allaqachon bor bo'lsa, qayta o'qishning hojati yo'q
            if (FirebaseRepo.familyCode != null && FirebaseRepo.childId != null) return

            val prefs = context.getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            val savedCode = prefs.getString("family_code", null)
            val savedChildId = prefs.getString("child_id", null)

            if (FirebaseRepo.familyCode == null) FirebaseRepo.familyCode = savedCode
            if (FirebaseRepo.childId == null) FirebaseRepo.childId = savedChildId
        }
    }
}
