# Oila Nazorati — Android ota-ona nazorati ilovasi

Bu loyiha **Android Studio**da ochib, qurish (build) uchun tayyor kod skeleti.
Kompilyatsiya qilingan APK emas — buni siz (yoki dasturchi) Android Studio'da
ochib, Firebase ulab, qurishingiz kerak.

## Nima qiladi (va nima qilmaydi)

✅ Yig'adi:
- Lokatsiya (har 15 daqiqada, taxminiy/aniq)
- Qo'ng'iroqlar: yo'nalishi (kiruvchi/chiquvchi/javobsiz), boshlanish-tugash
  vaqti, davomiyligi — **raqamsiz**
- SMS: yuborilgan/qabul qilingan hodisasi va vaqti — **matnsiz**
- Ilovalarda o'tkazilgan vaqt: ilova nomi, qachondan-qachongacha ochiq
  bo'lgani — **ilova ichidagi harakatlarsiz**

❌ Yig'MAYDI (ataylab, arxitektura darajasida):
- Telefon raqamlari, kontakt ismlari
- SMS/xabar matni
- Ijtimoiy tarmoq (WhatsApp, Telegram, Instagram va h.k.) xabarlari —
  bunga texnik yo'l yo'q, chunki bu ilovalar shifrlangan va ekranni
  "josuslik" qilib o'qish (Accessibility Service orqali) stalkerware
  hisoblanadi — bu loyihaga QASDAN qo'shilmagan

## Nega chegara shu yerda

`READ_SMS`/`READ_CALL_LOG` ruxsatlarini Google Play faqat ilova qurilmaning
**standart Telefon/SMS ilovasi** (default handler) bo'lgandagina beradi —
bu Google'ning qattiq siyosati, aylanib o'tib bo'lmaydi. Ijtimoiy tarmoq
xabarlarini o'qiydigan ilovalar esa muntazam ravishda Play Store'dan olib
tashlanadi (stalkerware siyosati). Shuning uchun bu loyiha faqat **statistika**
(vaqt, davomiylik, tur) yig'adi — mazmun emas.

## O'rnatish qadamlari

### 1. Firebase loyihasini sozlash
1. https://console.firebase.google.com → yangi loyiha yarating
2. **Authentication** → "Anonymous" provayderini yoqing
3. **Firestore Database** → yarating (production mode)
4. Firestore qoidalarini shu papkadagi `firestore.rules` fayli bilan
   almashtiring (Firebase konsoli → Firestore → Rules)
5. **Project settings → Your apps → Add app → Android**:
   - Package name: `uz.oilanazorati.parentcontrol`
   - `google-services.json` faylini yuklab oling
6. Yuklab olingan `google-services.json`ni `app/` papkasi ichiga qo'ying
   (`app/google-services.json`)

### 2. Android Studio'da ochish
1. Android Studio → Open → shu papkani tanlang
2. Gradle sync avtomatik boshlanadi
3. `app/google-services.json` joyida ekanini tekshiring

### 3. Ikkita qurilmada sinash
- **Bola qurilmasi**: ilovani o'rnating → "Bu farzandimning telefoni" →
  oila kodini kiritish → barcha ruxsatlarni berish (jumladan
  "Standart Telefon/SMS ilovasi" — bu qadam **Sozlamalar** ilovasiga
  olib boradi, foydalanuvchi o'zi tasdiqlashi kerak, dasturiy aylanib
  o'tib bo'lmaydi)
- **Ota-ona qurilmasi**: ilovani o'rnating → "Men ota-onaman" → xuddi
  shu oila kodini kiritish → statistika ko'rinadi

Oila kodi hozircha `ChildSetupActivity`da qo'lda 6 xonali kod sifatida
kiritilmoqda — ishlab chiqarishga chiqarishdan oldin buni ota-ona
tomonidan avtomatik generatsiya qilinadigan qismga bog'lash kerak
(masalan `ParentDashboardActivity`da "yangi kod yaratish" tugmasi).

## Play Store'ga joylashtirishdan oldin

1. **Ruxsat deklaratsiyasi formasi** — Play Console'da `READ_SMS`/
   `READ_CALL_LOG` uchun "Permission declaration form" to'ldirilishi
   shart, ilova nima uchun kerakligini aniq tushuntirish bilan
2. **Maxfiylik siyosati** — ota-ona nazorati ilovalari uchun alohida,
   aniq maxfiylik siyosati sahifasi majburiy (qaysi ma'lumot yig'ilishi,
   kim ko'ra olishi)
3. **"Nazorat ilovasi" belgisi** — Google Play'da bunday ilovalar alohida
   toifada ro'yxatdan o'tkaziladi, ko'proq tekshiruvdan o'tadi
4. Ilova ekranida (ChildSetupActivity) foydalanuvchiga aniq ko'rsatilishi
   kerak: kim, nimani, qachon ko'radi — bu kod ichida allaqachon
   "shaffoflik" tamoyili sifatida kiritilgan (yashirin rejim yo'q)

## Android Studio'siz APK olish (GitHub Actions orqali)

Kompyuteringizda Android Studio o'rnatmasdan ham APK olish mumkin —
GitHub buni bulutda o'zi quradi. Qadamlar:

1. **Firebase'dan `google-services.json` oling** (yuqoridagi "1-qadam"ga
   qarang), faylni matn muharriri bilan ochib, HAMMA matnini nusxalang
2. GitHub repo'ingizda: **Settings → Secrets and variables → Actions →
   New repository secret**
   - Nomi: `GOOGLE_SERVICES_JSON`
   - Qiymati: nusxalangan matn
3. Loyihani (shu papkani, `.github` papkasi bilan birga) GitHub'ga
   push qiling
4. Repo'ning **Actions** bo'limini oching — "Build APK" workflow avtomatik
   ishga tushadi (bir necha daqiqa davom etadi)
5. Workflow tugagach, o'sha sahifada pastda **Artifacts** qismida
   `OilaNazorati-debug-apk` degan faylni yuklab olasiz — bu ZIP ichida
   `app-debug.apk` bor, shuni telefoningizga o'tkazib o'rnatishingiz mumkin

Agar workflow push qilinganda avtomatik ishga tushmasa, Actions
bo'limida **"Run workflow"** tugmasini qo'lda bosishingiz mumkin.



- Bir nechta farzand qo'llab-quvvatlash (hozir bittaga mo'ljallangan)
- Geofence (xavfsiz hudud) ogohlantirishlari
- Ilovalarni bloklash/vaqt jadvali (Device Admin API kerak bo'ladi)
- Push-bildirishnoma (masalan "farzandingiz uydan chiqdi")
- Firestore qoidalarini kuchaytirish (hozirgi qoidalar demo darajasida,
  har qanday autentifikatsiya qilingan foydalanuvchi kodni bilsa kira
  oladi — ishlab chiqarishda buni albatta cheklash kerak)
