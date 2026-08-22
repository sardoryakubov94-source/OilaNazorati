package uz.oilanazorati.parentcontrol.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Ilovani "qurilma administratori" (Device Admin) maqomiga ega qilish
 * uchun. Bu maqom faol bo'lsa, Android ilovani ODDIY "O'chirish" tugmasi
 * orqali o'chirishga YO'L QO'YMAYDI — avval shu admin maqomini
 * Sozlamalar orqali bekor qilish kerak, va shu bekor qilish paytida
 * [onDisableRequested] orqali ko'rsatilgan ogohlantirish matni chiqadi.
 *
 * MUHIM CHEKLOV (ochiq va halol tan olinishi kerak): bu FAQAT qo'shimcha
 * to'siq (speed bump), TO'LIQ himoya EMAS. Agar kimdir qurilmaning
 * Sozlamalar > Xavfsizlik > Qurilma administratorlari bo'limini bilib,
 * shu yerdan admin huquqini o'chirsa — keyin ilovani oddiy o'chirish
 * mumkin bo'lib qoladi. Android'da ilovani HAQIQATDA "o'chirib
 * bo'lmaydigan" qilish faqat qurilmani factory reset qilib, "Device
 * Owner" rejimida (QR-kod orqali) qayta sozlashda mumkin — bu esa
 * butunlay boshqa, ancha katta jarayon.
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Diqqat: bu ilovani o'chirsangiz, farzandingiz uchun " +
            "sozlangan nazorat funksiyalari (qo'ng'iroq, SMS, joylashuv " +
            "kuzatuvi) butunlay to'xtaydi. Davom etmoqchimisiz?"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
