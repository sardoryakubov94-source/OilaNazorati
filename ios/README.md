# Oila Nazorati — iOS (qisman)

Bu papkada Swift kodi bor. **Men bu kodni bu yerdan build qila olmayman** —
`.ipa` fayl yasash uchun **Mac + Xcode** kerak. Quyida nima ishlayotgani,
nima ishlamasligi va qanday build qilishni toping.

## Avval — nima ISHLAYDI va nima ISHLAMAYDI

### Ishlaydi
- **Oila kodi bilan bog'lash** — Android'dagi bilan bir xil Firestore
  bazasiga yoziladi. Ota-ona Android ilovasida yaratgan kodni kiritasiz.
- **Joylashuvni kuzatish** — Ota-ona panelidagi "So'nggi joylashuv"
  kartasida iPhone ham ko'rinadi, hech qanday Android-tomon o'zgarishsiz.
- **Kontrol rejim** — Android ilovasidagi "Kontrol rejim" tugmasi (🛡)
  endi iPhone'ni ham boshqaradi. **Lekin**: iOS'da aniq bitta-bitta
  dastur tanlab bo'lmaydi (Apple ruxsat bermaydi) — shuning uchun
  yoqilganda deyarli **BARCHA** dastur toifalari bloklanadi (ijtimoiy
  tarmoq, o'yin, messenjer...). Telefon va Favqulodda chaqiruv — Apple
  o'zi himoyalagan, ular baribir ishlayveradi.

### UMUMAN ishlamaydi (Apple texnik jihatdan yopib qo'ygan, ruxsat so'rab
ham bo'lmaydi)
- SMS o'qish, qo'ng'iroqlar tarixi
- Bildirishnoma matnini o'qish (Telegram/Instagram xabarlari)
- Ekran suratini olish / "Xavf signali" (NSFW tahlil)
- Fonda mikrofon eshitish
- Kontaktlar ro'yxati

## Xcode'da qanday ishga tushirish

1. Xcode'da **yangi iOS App loyihasi** yarating (SwiftUI, Swift).
2. Shu papkadagi barcha `.swift` fayllarni (`Sources/`) loyihangizga
   sudrab tashlang ("Copy items if needed" belgilangan holda).
3. **Firebase**: [Firebase Console](https://console.firebase.google.com)
   da **xuddi shu loyihaga** (Android ilova turgan loyiha — `oilanazorat-3c8de`)
   yangi **iOS ilova** qo'shing. U sizga `GoogleService-Info.plist` fayl
   beradi — shuni yuklab, Xcode loyihangizga qo'shing.
4. **Swift Package**: File > Add Package Dependencies >
   `https://github.com/firebase/firebase-ios-sdk` — `FirebaseAuth` va
   `FirebaseFirestore` kutubxonalarini belgilang.
5. `Resources/Info-additions.plist` dagi kalitlarni loyihangizning asosiy
   Info.plist'iga qo'lda qo'shing (joylashuv ruxsat matnlari + fon rejimi).
6. `Resources/OilaNazoratiIOS.entitlements` faylini loyihaga qo'shing va
   target > Signing & Capabilities > "+ Capability" > **Family Controls**
   ni yoqing.
7. **MUHIM — Apple'dan alohida ruxsat**: Family Controls entitlement'i
   oddiy Developer akkauntga avtomatik berilmaydi. Apple'ga so'rov
   yuborish kerak:
   https://developer.apple.com/contact/request/family-controls-distribution
   Apple buni ko'rib chiqadi (bir necha kun/hafta), faqat shundan keyin
   TestFlight/App Store'ga chiqarish mumkin bo'ladi (Xcode'da simulyator/
   o'z qurilmangizda sinab ko'rish uchun shart emas, lekin tarqatish uchun
   kerak).
8. Build qiling, o'z iPhone'ingizga (Developer akkaunt bilan) o'rnating.

## Build qilib ko'rmaganman

Bu kod Xcode'da kompilyatsiya qilinmagan — `ManagedSettings`/`FamilyControls`
API nomlari iOS versiyalari orasida sal farq qilishi mumkin. Xatolik chiqsa,
Xcode'ning o'zi aniq qaysi qatorni va qanday tuzatishni ko'rsatadi (odatda
kichik nom farqi bo'ladi, mantiq to'g'ri).


## Platforma va qurilma roli boshqaruvi (yangi)

`Sources/DeviceContext.swift` yagona siyosat qatlamini beradi:

- `DeviceRole` — qurilma ota-onaniki yoki farzandniki ekanini belgilaydi.
- `DevicePlatform` — Android/iOS/aniqlanmagan platformani ifodalaydi.
- `ChildPlatformResolver` — Firestore'dagi `childPlatform` va eski `platform` maydonlarini bir xil talqin qiladi.
- `DeviceCapabilityMatrix` — bola qurilmasi platformasiga qarab mavjud nazorat imkoniyatlarini qaytaradi.
- `CrossPlatformConfiguration` — ota-ona interfeysi platformasi va bola imkoniyatlarini birlashtiradi. Noma'lum bola platformasida imkoniyatlar yopiq qoladi.

### To'rtta kombinatsiya uchun qoida

| Ota-ona qurilmasi | Bola qurilmasi | Ota-ona interfeysi | Bola uchun imkoniyatlar |
|---|---|---|---|
| iOS | iOS | iOS ko'rinishi | iOS ruxsat bergan imkoniyatlar |
| Android | iOS | Android ko'rinishi | iOS ruxsat bergan imkoniyatlar |
| iOS | Android | iOS ko'rinishi | Android imkoniyatlari |
| Android | Android | Amaldagi Android ko'rinishi | Android imkoniyatlari |

Bu jadval — markaziy siyosat. U hali to'rtta yo'nalishning barchasi ishlaydi degani emas: iOS ota-ona paneli va iOS ichida Android bolaning to'liq boshqaruv interfeysi keyingi bosqichlarda ulanadi. Android ilovasining mavjud ota-ona paneli va uning Android→Android ishlashiga bu branchda tegilmagan.

Yangi iOS o'rnatilishida qurilma roli tanlanadi. Avvaldan oila kodiga ulangan iOS qurilmalari avtomatik ravishda `child` rolida davom etadi. iOS bola bog'lanayotganda Firestore hujjatiga `childPlatform: ios`, `platform: ios` va imkoniyatlar ro'yxati yoziladi. Android ilovasining mavjud `childPlatform: android` maydoni ham saqlanadi.

**Hozirgi cheklov:** iOS ota-ona rejimi hozircha aniq ogohlantiruvchi ekran bilan to'xtaydi; to'liq ota-ona paneli sifatida ko'rsatilmaydi. `focus_mode` iOS'da Family Controls ruxsati va Apple entitlement'iga bog'liq. Platforma bo'yicha qo'llab-quvvatlash mavjudligi — foydalanuvchi ruxsat bergan yoki funksiya real qurilmada ishlayotganini anglatmaydi.


## Android ota-ona panelida platformaga mos ko'rinish (keyingi bosqich)

`ParentFamilyLinkActivity` bolalar ro'yxatidagi qurilma platformasini ko'rsatadi va iOS bolani tanlashni bloklamaydi. Tanlangan bola platformasi Firestore hujjatidan qayta tekshiriladi; noma'lum platforma bo'lsa boshqaruv paneli ochilmaydi.

Android ota-ona paneli iOS bola uchun hozircha faqat:
- Joylashuv va joylashuv tarixini;
- iOS Screen Time / Family Controls orqali ishlaydigan umumiy Kontrol rejimini

ko'rsatadi. Qo'ng'iroqlar, SMS, kontaktlar, ilovalar statistikasi, screenshot, bildirishnoma, SIM, jonli mikrofon va xavfsizlik signallari kabi Android'ga xos bo'limlar yashiriladi, ularga tegishli listenerlar ham ulanmaydi. iOS'da alohida ilovalarni tanlab bloklash Android'dagi kabi mavjud emas; ota-ona faqat umumiy yoqish/o'chirishni yuboradi.

Android bola tanlanganda mavjud dashboard ko'rinishi va funksiyalar matritsasi o'zgarmaydi. Platforma metama'lumoti yo'q bo'lgan eski/nomalum yozuvlar uchun Android imkoniyatlari taxmin qilinmaydi.
