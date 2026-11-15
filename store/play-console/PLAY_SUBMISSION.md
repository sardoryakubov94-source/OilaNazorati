# Google Play topshirish paketi (Bulut servis / OilaNazorati)

> Bu hujjat Play Console formalarini **halol** to'ldirish uchun. Google qarorni o'zi chiqaradi,
> natija kafolatlanmaydi. Noto'g'ri ma'lumot yozish yoki ruxsatni yashirish akkauntni bloklashga olib keladi.

## 1. Ruxsatlar bo'yicha xavf jadvali (manifest asosida)

| Ruxsat / xizmat | Play holati | Xavf |
|---|---|---|
| READ_SMS, RECEIVE_SMS, SEND_SMS | SMS/Call Log deklaratsiyasi shart; ruxsat etilganlar: standart SMS/telefon ilovasi | YUQORI |
| READ_CALL_LOG, CALL_PHONE, ANSWER_PHONE_CALLS | Xuddi shu guruh | YUQORI |
| AccessibilityScreenshotService | Accessibility API deklaratsiyasi; boshqa ilovalarni kuzatish uchun foydalanish odatda ruxsat etilmaydi | YUQORI |
| AppDeviceAdminReceiver ("himoyasi") | O'chirib tashlashga to'sqinlik qilsa, Stalkerware siyosatiga zid | YUQORI |
| ACCESS_BACKGROUND_LOCATION | Deklaratsiya + video kerak | O'RTA |
| RECORD_AUDIO + foreground service (mikrofon) | Foydalanuvchiga aniq bildirishnoma va ma'lumot kerak | O'RTA |
| SocialNotificationListenerService | Boshqa ilovalar bildirishnomasi; ochiq ma'lumot va rozilik kerak | O'RTA |
| MediaProjection (screenshot, jonli ekran) | Foreground service + ochiq ruxsat | O'RTA |
| PACKAGE_USAGE_STATS, READ_CONTACTS | Maxfiylik siyosati va Data Safety | PAST |

Natija: ilova shu holicha Play'dan o'tishi ehtimoli **past**. Eng ishonchli yo'l: Play uchun alohida
versiya (YUQORI xavfli qismlarsiz), to'liq versiya saytda (oilanazorati.uz).

## 2. SMS / Call Log deklaratsiyasi (Play Console → App content)

Asosiy vazifa matni (inglizcha, formaga qo'yish uchun):

> Bulut servis is a parental-control app. The parent installs it on the child's own phone and links it to the
> parent's app with a family code. The child (device owner) and the parent accept an in-app consent screen before
> any permission is requested. The app shows the call log and SMS metadata of the child's device to the linked
> parent so that a parent can protect a minor from harmful contacts. The data is sent only to the linked parent's
> account, is not sold or shared with third parties, and monitoring stops when the family link is removed.

Eslatma: Google bu holatni "Family or device locator" / "Remote control" toifasiga kiritishi mumkin. Rad etilsa:
ruxsatlarsiz versiya yuborib, to'liq versiyani saytda qoldiring.

## 3. Ilova ichidagi ochiq ma'lumot (prominent disclosure)
- Mavjud: bola va ota-ona rozilik oynalari (`ChildLegalConsentGate`, `ParentLegalNoticeGate`), qo'ng'iroq, SMS, joylashuv, ekran, mikrofon nomlari bilan.
- Google talabi: ruxsat so'ralishidan **darhol oldin** ma'lumot, "Qabul qilaman / Rad etaman" tugmalari. Tekshirish kerak: ruxsat oynalari rozilikdan keyin ochilishi.

## 4. Data Safety (qoralama javoblar)
- Yig'iladi: joylashuv, SMS, qo'ng'iroq tarixi, kontaktlar, ilova faoliyati, audio (faqat ota-ona so'rasa), fotosurat/ekran tasvirlari, qurilma identifikatori.
- Maqsad: faqat ilova funksiyasi (ota-ona nazorati). Reklama yo'q, sotilmaydi.
- Uzatiladi: ulangan ota-ona akkauntiga (Firebase). Shifrlangan uzatish: ha (HTTPS).
- O'chirish: rozilikni qaytarish va ulanishni uzish orqali; maxfiylik siyosatida yo'li ko'rsatilsin.

## 5. Stalkerware siyosati talablari (tekshiring)
- Ilova ikoni va nomi ko'rinib tursin (Bulut servis, yashirinmaydi).
- Kuzatuv paytida doimiy bildirishnoma bo'lsin (foreground service).
- Reklamada "yashirin kuzatuv" so'zlari bo'lmasin.
- Ilovani o'chirishga to'sqinlik qiluvchi himoya (device admin) bo'lsa, Play versiyasidan olib tashlang yoki oddiy o'chirishga ruxsat bering.

## 6. Review uchun yozuv (reviewer notes) va akkaunt
- Test uchun ota-ona Google akkaunti va oila kodi, ikki qurilma bilan qadamlar.
- Bola qurilmasida har bir ruxsat qaysi funksiya uchun ekani.
- Maxfiylik siyosati: https://oilanazorati.uz/privacy.html, shartnoma: https://oilanazorati.uz/terms.html

## 7. Topshirish tartibi
1. Play Console: ilova yarating, AAB yuklang (Internal testing).
2. App content: Privacy policy, Data safety, SMS/Call Log, Accessibility, Background location deklaratsiyalari.
3. Natijani kuting. Rad etilsa sabab matnini yuboring, ruxsatni olib tashlab qayta yuboramiz.
