# =====================================================================
# Oila Nazorati — release (R8/ProGuard) qoidalari
#
# MUHIM: minifyEnabled ilgari "false" edi (hech qanday shrinking
# bo'lmagan), shuning uchun bu loyihada bu qoidalar ilgari HECH QACHON
# sinovdan o'tmagan. Quyidagi keep-qoidalar aynan shu sabab — reflection
# orqali ishlaydigan joylarni R8 "ishlatilmayapti" deb xato o'chirib
# yubormasligi uchun — ataylab keng va ehtiyotkorona yozilgan.
# =====================================================================

# --- Firestore modellar (toObject() reflection orqali maydonlarni
# to'ldiradi — R8 buni "ko'rmaydi", shuning uchun nomlarini
# o'zgartirsa/olib tashlasa, maydonlar jim-jimgina null bo'lib qoladi).
-keep class uz.oilanazorati.parentcontrol.model.** { *; }
-keepclassmembers class uz.oilanazorati.parentcontrol.model.** { *; }

# --- Umuman shu package ichidagi barcha data class'lar (kelajakda
# yangi model qo'shilsa ham xavfsiz bo'lishi uchun) — no-arg
# konstruktorlar va getter/setterlar Firestore uchun saqlanadi.
-keepclassmembers class uz.oilanazorati.parentcontrol.** {
    public <init>();
}

# --- Generic turlar (List<T>, Map<K,V> maydonlar) uchun Firestore'ga
# kerakli signature ma'lumoti.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# --- WebRTC: native (JNI) tomon Java klasslarini nom bo'yicha chaqiradi.
# R8 buni ko'rmaydi, shuning uchun butun paketni saqlaymiz (xavfsizlik
# uchun — hajmni ozroq kamaytirsa ham, ishlashdan afzal).
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# --- Firebase / Play Services / gRPC ko'pincha ixtiyoriy sinflarga
# murojaat qiladi (build muhitida yo'q bo'lishi mumkin) — bular haqida
# ogohlantirish build'ni to'xtatmasin.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.Modifier
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Kotlin metadata (ba'zi kutubxonalar buni reflection uchun o'qiydi)
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.coroutines.jvm.internal.DebugMetadata { *; }
