# Oila Nazorati — iOS

iOS ilovasi SwiftUI asosida tayyorlanadi. Ushbu branch iOS bola va ota-ona panellarini, Apple Screen Time modullarini hamda Xcode project yaratish konfiguratsiyasini o'z ichiga oladi.

> **Hozirgi holat:** manba kod va XcodeGen konfiguratsiyasi tayyorlanmoqda. Ushbu muhitda macOS/Xcode yo'q, shuning uchun iOS targetlari hali Xcode'da build qilinmagan va haqiqiy iPhone'da sinovdan o'tkazilmagan. Apple entitlement tasdig'i va signing talab qilinadi.

## 1. iOS bola paneli

- Oila kodi orqali mavjud Firebase oilasiga ulanish.
- Joylashuvni oilaga ulashish uchun alohida, bolaning o'zi yoqadigan rozilik tugmasi.
- Family Controls ruxsat holatini ko'rsatish va bolaning qurilmasida ruxsat so'rash.
- Apple FamilyActivityPicker orqali ilova, toifa va saytlarni bolaning o'z iPhone'ida tanlash.
- Kunlik va haftalik DeviceActivity hisobotlari; tanlangan ilovalar va toifalar bo'yicha foydalanish vaqti.
- Ota-ona yuborgan kunlik limit, maktab va uyqu jadvalini olish.
- DeviceActivityMonitor orqali limit/jadval hodisalarida ManagedSettings cheklovlarini faollashtirish.
- Limit tugaganda bolaning qurilmasiga mahalliy bildirishnoma.
- Limit hodisasini App Group'da navbatga qo'yish va bola ilovasi keyingi safar faol bo'lib internetga ulanganda Firebase'ga yuborish.

## 2. iOS ota-ona paneli

- Google Sign-In va Firebase Auth.
- ownerUid bo'yicha faqat joriy hisob egasining oilasini topish.
- Bolalar ro'yxati, qurilma platformasi, joylashuv va bola iPhone'idan sinxronlangan Apple Screen Time ruxsat holati.
- iOS bola uchun kunlik limitni belgilash.
- Maktab va uyqu vaqtlarini belgilash.
- Tanlangan saytlarni cheklashni yoqish/o'chirish.
- Sozlamalarni families/{code}/children/{uid}/screen_time_settings/current manziliga yuborish.
- Bola qurilmasidan Firebase'ga kelgan limit hodisalari tarixini ko'rish.
- Android bola tanlanganda mavjud Android ko'rinishi saqlanadi; iOS-only boshqaruv Android qurilmaga ko'rsatilmaydi.

**Ilova tanlovi bolaning iPhone'ida qilinadi.** FamilyActivityPicker bolaning qurilmasidagi Apple authorization bilan ishlaydi; ota-ona boshqa qurilmadan bolaning tokenlarini o'zboshimchalik bilan yaratmaydi. Ota-ona limit va jadval siyosatini yuboradi, bola esa ruxsat berganidan keyin tanlangan ilova/toifalarga shu siyosat tatbiq qilinadi.

## 3. Platformalararo boshqaruv matritsasi

Uchta asosiy kombinatsiya kodda alohida ajratilgan:

- **iOS ota-ona → iOS bola:** ota-ona iOS panelidan kunlik limit, maktab/uyqu jadvali va sayt cheklov siyosatini yuboradi. Bola iPhone'ida Apple ruxsati va FamilyActivityPicker tanlovi asosida qo'llanadi. Joylashuv faqat bolaning roziligi bilan.
- **Android ota-ona → iOS bola:** Android ilovasi bolaning `supportedCapabilities` ro'yxatini Firestore'dan o'qiydi. Ekran vaqti tugmasi iOS bola uchun maxsus siyosat oynasini ochadi va `screen_time_settings/current` ga limit, jadval va sayt cheklovlarini yuboradi. Batafsil ilova/toifa hisobotlari Android ota-onaga eksport qilinmaydi.
- **iOS ota-ona → Android bola:** iOS ota-ona paneli Android bolaning mavjud faoliyat ko'rsatkichlari va joylashuvini ko'rsatadi; umumiy Kontrol rejimini `focus_mode_settings/current` orqali yuboradi hamda `screenshot_requests/current` orqali screenshot so'rashi va oxirgi rasmni ko'rishi mumkin. Android'dagi SMS, qo'ng'iroq, screenshot va boshqa ma'lumotlar faqat bola Android ilovasida tegishli ruxsat/modul faol bo'lsa mavjud bo'ladi.

Noma'lum platforma yoki e'lon qilinmagan imkoniyatlar avtomatik yoqilmaydi. Android ota-ona paneli iOS uchun faqat iOS bola ilovasi e'lon qilgan Screen Time siyosat imkoniyatlarini ko'rsatadi.

## 3. Apple platformasi bo'yicha muhim chegaralar

- SMS mazmuni, qo'ng'iroqlar tarixi, kontaktlar, boshqa ilovalarning bildirishnoma matni, yashirin mikrofon va screenshot monitoringi iOS'da uchinchi tomon ota-ona ilovasiga Android'dagi kabi ochiq API bilan berilmaydi.
- Screen Time ilova/toifa hisobotlari DeviceActivityReport extension ichida maxfiylik sandboxida chiziladi. Shu sabab batafsil ilova/toifa hisobotlari hozircha bolaning iPhone'ida ko'rsatiladi; ularni Firebase orqali ota-onaga yuborish yoqilmagan.
- Apple'ning FamilyActivityData orqali batafsil usage ma'lumotini ilovadan eksport qilish API'si Family Controls App and Website Usage entitlementini va approvedWithDataAccess ruxsatini talab qiladi. Apple hujjatlariga ko'ra, mijozlar uchun bu data-access API faqat EU hududidagi Apple Account bilan ishlaydi. O'zbekistondagi foydalanuvchi uchun bu yo'lni hozir ishlaydi deb hisoblash mumkin emas.
- Limit tugaganidagi hodisa bola qurilmasida navbatga qo'yiladi. Hozircha ota-onaga real-time push xabari emas, bola ilovasi qayta ochilib Firebase'ga sinxronlangandan keyin ota-ona panelidagi hodisalar ro'yxati ishlaydi.
- iOS fon joylashuvi faqat bolaning roziligi va iOS ruxsat darajasiga bog'liq; yangilanish chastotasini iOS boshqaradi.


**Apple Family Sharing ham kerak:** bolaning iPhone'ida Family Controls ruxsatini so'raganda ota-ona yoki vasiy shu Apple Family Sharing guruhida bo'lishi va tizim oynasida so'rovni tasdiqlashi kerak. Oila Nazorati ichidagi Google hisob/oila kodi Apple Family Sharing o'rnini bosmaydi.

## 4. Xcode project yaratish

Mac kompyuterda:

1. Xcode va Xcode Command Line Tools o'rnating.
2. XcodeGen o'rnating: brew install xcodegen.
3. Terminalda repository ichidagi ios papkasiga o'ting.
4. xcodegen generate buyrug'ini bajaring.
5. OilaNazorati.xcodeproj loyihasini Xcode'da oching.

project.yml quyidagi uchta targetni yaratadi:
- OilaNazoratiIOS — asosiy iOS ilova;
- OilaNazoratiActivityMonitor — Device Activity Monitor extension;
- OilaNazoratiActivityReport — Device Activity Report extension.

## 5. Siz Apple Developer hisobida bajaradigan ishlar

1. Apple Developer Program a'zoligini faollashtiring.
2. Bundle ID uz.oilanazorati.parentcontrol ni Apple Developer portalida ro'yxatdan o'tkazing. Agar Firebase'da boshqa iOS Bundle ID ishlatsangiz, ios/project.yml dagi uchta Bundle ID'ni moslang.
3. App ID va uchala target uchun App Groups capability'ni yoqing va group.uz.oilanazorati.shared guruhini yarating.
4. Xcode'da asosiy ilova, Device Activity Monitor va Device Activity Report targetlarining har biriga Family Controls capability qo'shing.
5. Apple'ga Family Controls distribution entitlement so'rovini yuboring. Asosiy ilova bilan birga Screen Time ishlatadigan ikkala extension uchun ham so'rov talab qilinadi. Tasdiq avtomatik berilmaydi.
6. Firebase Console > Authentication > Sign-in method bo'limida Google va Anonymous provider'larini yoqing. Google provider ota-ona kirishi, Anonymous provider esa bola qurilmasining oilaga ulanishi uchun kerak.
7. Firebase Console'da oilanazorat-3c8de loyihasiga iOS app qo'shing. Bundle ID aynan Xcode targetdagi ID bilan bir xil bo'lsin. GoogleService-Info.plist ni yuklab, asosiy app targetiga qo'shing.
8. GoogleService-Info.plist ichidagi REVERSED_CLIENT_ID ni Xcode > Target > Info > URL Types > URL Schemes bo'limiga qo'shing.
9. Signing & Capabilities bo'limida Apple Team va Automatic Signing'ni sozlang; entitlement tasdiqlangach provisioning profile'larni yangilang.
10. Avval bola iPhone'ida ilovani ishga tushiring, Family Controls ruxsatini bering, so'ng FamilyActivityPicker'da nazorat qilinadigan ilova/toifa/saytlarni tanlang.
11. Ota-ona iPhone'ida Google hisob bilan kiring va limit/jadvallarni sinang.

## 6. Firebase ma'lumotlar sxemasi

Ota-onadan bolaga siyosat:
families/{code}/children/{uid}/screen_time_settings/current

Maydonlar:
- enabled
- dailyLimitMinutes
- schoolEnabled, schoolStart, schoolEnd
- sleepEnabled, sleepStart, sleepEnd
- websiteBlockingEnabled
- updatedAtMs, updatedBy, schemaVersion

Bola qurilmasidan limit hodisalari:
families/{code}/children/{uid}/screen_time_reports/{eventId}

Firestore Security Rules ushbu yo'llarda faqat oila egasi siyosat yozishi va tegishli bola qurilmasi o'z hodisasini yozishi mumkinligini tekshirishi shart. Ochiq allow read, write: if true qoidasini ishlatmang.


### Firestore Security Rules

The repository's `firestore.rules` now contains dedicated rules for iOS Screen Time:
- only the real family owner can create/update `screen_time_settings/current`;
- the paired child session can read its policy;
- the child session can append only validated `daily_limit_reached` events;
- the family owner can read event history;
- the general child-subcollection wildcard explicitly excludes both Screen Time collections.

**This file is not automatically deployed by a GitHub commit.** Before testing the parent/child sync, copy the current root `firestore.rules` to Firebase Console → Firestore Database → Rules and publish it. Do not loosen the existing rules to public read/write.

## 7. Build va sinov holati

- [x] iOS bola va ota-ona UI modullari
- [x] Firebase siyosat sinxronlash kodi
- [x] FamilyActivityPicker va Screen Time authorization oqimi
- [x] DeviceActivityMonitor extension manbasi
- [x] Kunlik/haftalik app va category report extension manbasi
- [x] App Group va extension entitlement fayllari
- [x] XcodeGen orqali uchta target yaratish konfiguratsiyasi
- [x] Android ota-ona ilovasida iOS bolaning capability ro'yxatini o'qish va Screen Time siyosatini yuborish
- [x] iOS ota-ona panelida Android bola uchun umumiy Kontrol rejimini yuborish
- [x] Platforma almashtirilganda Android ota-ona interfeysining asl ko'rinishini tiklash
- [ ] Mac/Xcode'da project generate va compile
- [ ] GoogleService-Info.plist va Google URL scheme
- [ ] Apple Family Controls development/distribution entitlement tasdig'i
- [ ] App Group va provisioning profile signing
- [ ] Haqiqiy iPhone'da ruxsat, limit, jadval, sayt va hisobot sinovi
- [ ] Firebase Security Rules va real ota-ona/bola qurilmalararo sinov
- [ ] Ota-onaga server push xabari (FCM backend) — alohida server sozlamasi talab qiladi

**Hech bir iOS funksiyasi Xcode build va haqiqiy qurilma sinovisiz 100% tayyor deb belgilanmaydi.**