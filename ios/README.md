# Oila Nazorati — iOS

iOS ilovasi SwiftUI asosida tayyorlanadi. Ushbu branch iOS bola va ota-ona panellarini, Apple Screen Time modullarini hamda Xcode project yaratish konfiguratsiyasini o'z ichiga oladi.

> **Hozirgi holat:** GitHub Actions orqali XcodeGen loyihasi yaratiladi, iOS Simulator Debug va jismoniy iPhone uchun Release konfiguratsiyasi kompilyatsiya qilinadi. Bu imzolangan App Store arxivi yoki haqiqiy iPhone'dagi end-to-end sinov o'rnini bosmaydi. Apple Family Controls entitlement tasdig'i, signing va Firebase iOS konfiguratsiyasi hali talab qilinadi.

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

To'rtta qurilma kombinatsiyasi kodda alohida ajratilgan:

- **Android ota-ona → Android bola:** Android ota-ona paneli Android bolaning amaldagi Android imkoniyatlarini boshqaradi; mavjud screenshot, joylashuv, ilova faoliyati, qo'ng'iroq va SMS modullari shu platforma oqimida qoladi.
- **Android ota-ona → iOS bola:** Android ilovasi bolaning `supportedCapabilities` ro'yxatini Firestore'dan o'qiydi. iOS bola uchun Screen Time siyosati yuboriladi. Jonli ekran ulash ham mavjud: ota-ona Premium tekshiruvidan o'tadi, bola iPhone'da so'rovni ko'rib ReplayKit tizim oynasida uzatishni o'zi boshlaydi. Yashirin ekran yozuvi yo'q.
- **iOS ota-ona → Android bola:** iOS ota-ona paneli Android bolaning screenshot tarixini, qo'ng'iroqlar, SMS va kontaktlar bo'yicha sinxronlangan ma'lumotlarini hamda qo'llab-quvvatlanadigan boshqa Android ma'lumotlarini ko'rsatadi. Android bola uchun jonli ekran uzatish yo'q — screenshot funksiyasi saqlanadi.
- **iOS ota-ona → iOS bola:** ota-ona iOS panelidan kunlik limit, maktab/uyqu jadvali va sayt cheklov siyosatini yuboradi. Bola iPhone'ida Apple ruxsati va FamilyActivityPicker tanlovi asosida qo'llanadi. Joylashuv va jonli ekran/ovoz funksiyalari tegishli bolaning roziligi va iOS tizim oynalari bilan ishlaydi.

Noma'lum platforma yoki e'lon qilinmagan imkoniyatlar avtomatik yoqilmaydi. Android ota-ona paneli iOS uchun faqat iOS bola ilovasi e'lon qilgan Screen Time siyosat imkoniyatlarini ko'rsatadi.

## 3. Apple platformasi bo'yicha muhim chegaralar

- SMS mazmuni, qo'ng'iroqlar tarixi, kontaktlar, boshqa ilovalarning bildirishnoma matni, yashirin mikrofon va screenshot monitoringi iOS'da uchinchi tomon ota-ona ilovasiga Android'dagi kabi ochiq API bilan berilmaydi.
- Screen Time ilova/toifa hisobotlari DeviceActivityReport extension ichida maxfiylik sandboxida chiziladi. Shu sabab batafsil ilova/toifa hisobotlari hozircha bolaning iPhone'ida ko'rsatiladi; ularni Firebase orqali ota-onaga yuborish yoqilmagan.
- Apple'ning FamilyActivityData orqali batafsil usage ma'lumotini ilovadan eksport qilish API'si Family Controls App and Website Usage entitlementini va approvedWithDataAccess ruxsatini talab qiladi. Apple hujjatlariga ko'ra, mijozlar uchun bu data-access API faqat EU hududidagi Apple Account bilan ishlaydi. O'zbekistondagi foydalanuvchi uchun bu yo'lni hozir ishlaydi deb hisoblash mumkin emas.
- Limit tugaganidagi hodisa bola qurilmasida navbatga qo'yiladi. Bola ilovasi qayta ochilib Firebase'ga sinxronlangach, ota-ona paneli ochiq bo'lsa iOS'da in-app ogohlantirish, Android'da esa Screen Time oynasida jonli hodisa/toast ko'rsatiladi. Ilova yopiq paytda keladigan server push (FCM/APNs) hali ulanmagan.
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
- [x] GitHub Actions'da XcodeGen project generate va iOS Simulator/physical-device Release compile tekshiruvi (CI)
- [ ] Firebase iOS app uchun GoogleService-Info.plist va Google URL scheme (Firebase Console'dan olinadi)
- [ ] Apple Family Controls development/distribution entitlement tasdig'i
- [ ] App Group va provisioning profile signing
- [ ] Imzolangan App Store archive/IPA va haqiqiy iPhone'da ruxsat, limit, jadval, sayt, ReplayKit va hisobot sinovi
- [ ] Firebase Security Rules'ni deploy qilish va real ota-ona/bola qurilmalararo sinov
- [ ] Ota-onaga server push xabari (FCM backend) — alohida server sozlamasi talab qiladi

**Hech bir iOS funksiyasi Xcode build va haqiqiy qurilma sinovisiz 100% tayyor deb belgilanmaydi.**
## iOS ReplayKit live screen (development branch)

- Parent sends a `screen_requests/current` request; the iOS child must open the app and explicitly press **Ekran uzatishni boshlash**.
- The child then confirms the iOS ReplayKit Broadcast system dialog. The red system broadcast indicator is visible; stopping in Control Center/system UI ends capture.
- Screen video uses Agora RTC (separate channel/token purpose); the existing voice request and Android Agora voice services are not replaced.
- The Broadcast Upload Extension target is `OilaNazoratiScreenBroadcast`; it requires the registered App Group `group.uz.oilanazorati.shared` on both app and extension targets.
- Android parent live viewer currently supports an iOS child stream. iOS parent viewer supports iOS child stream. Android child live screen publication and Android→iOS live screen viewing remain a separate implementation/testing item; existing Android screenshot capture is unchanged.
- Firebase Functions and Firestore rules must be deployed before live screen tokens/requests work. Never place the Agora App Certificate in the app or repository.
- Required final verification: Xcode simulator build (extension target), Android Gradle build, deployed Function/rules, and physical iPhone↔Android/iPhone Broadcast tests. CI/real-device success has not been asserted from code changes alone.


## Store assetlari va privacy manifest

- iOS App Store uchun 1024×1024 ikonka va Google Play uchun 512×512 ikonka `ios/scripts/generate_app_icon.swift` orqali CI/Codemagic build paytida yaratiladi.
- Asosiy iOS ilova, DeviceActivityMonitor va ReplayKit extension'larda `PrivacyInfo.xcprivacy` manifestlari mavjud. App Store Connect'dagi privacy label'lar shu ma'lumot yig'ish ro'yxati bilan bir xil bo'lishi shart.
- App Store uchun imzolangan archive, provisioning profiles, Apple Family Controls distribution entitlement va haqiqiy qurilma sinovi hali alohida talab qilinadi.
