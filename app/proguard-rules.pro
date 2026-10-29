# Pingolin Note ProGuard Rules

# ---- UniFFI / JNA ----
-keep class uniffi.** { *; }
-keep class uniffi.pingolin_mobile.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**

# ---- Retrofit ----
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
# NO wildcard retrofit2 keep. retrofit-3.0.0.jar ships its own
# META-INF/proguard/retrofit2.pro with the precise full-mode rules (the
# @retrofit2.http interface keeps, Response/Continuation allowshrinking) —
# a blanket keep would freeze 45,978 bytes of dex for nothing.
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---- OkHttp ----
# No keeps: okhttp-android's AAR ships consumer rules (proguard.txt — dontwarn
# only; OkHttp is reflection-free and R8-safe by design). A wildcard
# -keep class/interface okhttp3.** pair would hold the entire client + internals
# unshrunk and unobfuscated for no consumer.
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- Trust (util/CompromisedKeyTrust.kt) ----
# The platform calls these by reflection, and the app never calls them from
# outside, so R8 would rename or drop them: X509TrustManagerExtensions finds the
# trust manager's host-aware checks and isSameTrustConfiguration with getMethod
# whenever it is given ours. The editor WebView's verifier makes one over the
# default trust (CompromisedKeyTrustProvider's), and OkHttp's chain cleaner does
# too. Without them, the editor's WebView reaches none of our hosts in a release
# build.
-keepclassmembers class com.pingolin.note.util.CompromisedKeyTrustManager {
    public java.util.List checkServerTrusted(java.security.cert.X509Certificate[], java.lang.String, java.lang.String);
    public java.util.List checkServerTrusted(java.security.cert.X509Certificate[], byte[], byte[], java.lang.String, java.lang.String);
    public boolean isSameTrustConfiguration(java.lang.String, java.lang.String);
}

# ---- Gson ----
# NO wildcard gson keep. gson-2.13.1.jar ships META-INF/proguard/gson.pro
# with the precise full-mode rules (TypeToken subclasses, adapter <init>s,
# the @SerializedName -if chain), whereas a blanket keep would freeze
# 115,811 bytes of dex. The specific keeps below stay because they are
# OURS, not the library's.
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- Hilt / Dagger ----
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }

# ---- DTOs (keep for Gson serialization) ----
-keep class com.pingolin.note.data.remote.dto.** { *; }
-keepclassmembers class com.pingolin.note.data.remote.dto.** { *; }

# ---- Room Entities ----
-keep class com.pingolin.note.data.local.entity.** { *; }
-keepclassmembers class com.pingolin.note.data.local.entity.** { *; }

# ---- Kotlin Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- Firebase Messaging ----
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ---- SQLCipher ----
# No keep: sqlcipher-android's AAR ships precise consumer rules for exactly
# what JNI resolves by name — native methods, constructors, mNativeHandle,
# and the SQLiteCustomFunction/PagerStats members the C side dispatches to.
# An app-side wildcard would hold every class whole on top of that.
-dontwarn net.zetetic.**

# ---- Coil (Coil 3 — coil3 package) ----
-dontwarn coil3.**

# ---- AndroidX Biometric ----
-keep class androidx.biometric.** { *; }

# ---- R$attr / R$color / R$styleable ----
# theme.resolveAttribute(R.attr.X, ...) and
# obtainStyledAttributes need the static R$attr fields present at
# runtime; R8 will inline the int IDs at the call site and then
# prune the fields, causing NoSuchFieldError on first access.
#
# `-keep` (not `-keepclassmembers`) is required — the latter only
# preserves members when the class itself is kept, and even then
# allows R8 to remove an "unused-after-inlining" field. `-keep`
# treats the field declaration as a public entry point and won't
# strip it.
-keep class **.R$attr { public static <fields>; }
-keep class **.R$color { public static <fields>; }
-keep class **.R$styleable { public static <fields>; }

# ---- UnifiedPush ----
-keep class org.unifiedpush.android.connector.** { *; }
-dontwarn org.unifiedpush.android.connector.**

# ---- Strip verbose logging from release ----
# The release build runs proguard-android-optimize (optimization on), so
# -assumenosideeffects lets R8 drop these calls AND the argument-building
# code (string concat, etc.) that feeds them. Removes debug/verbose/info
# logging — including any that would otherwise carry identifying data — from
# the shipped binary. Log.w / Log.e are deliberately kept for crash triage.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
