# RingBridge ProGuard / R8 rules.
#
# Minification is currently disabled in build.gradle.kts (isMinifyEnabled = false).
# These rules exist so that enabling R8 later is safe and doesn't strip/rename the
# reflection- and JNI-bound classes the app depends on.

# ── Kotlin metadata & coroutines ──────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Room ──────────────────────────────────────────────────────────────────────
# Room generates implementations by name and uses entities/DAOs reflectively.
-keep class dev.ringbridge.db.** { *; }
-keep @androidx.room.Database class * { *; }
-dontwarn androidx.room.paging.**

# ── Nordic BLE Library ────────────────────────────────────────────────────────
-keep class no.nordicsemi.android.ble.** { *; }
-dontwarn no.nordicsemi.android.ble.**

# ── JieLi auth native lib (libjl_auth.so) ─────────────────────────────────────
# Not currently loaded from Kotlin, but bundled. If/when a JNI bridge is added,
# its native-bound class must be kept so R8 doesn't rename methods the .so resolves
# by signature. Adjust the class name to match the bridge when it's introduced.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── MPAndroidChart ────────────────────────────────────────────────────────────
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ── OkHttp ────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── ZXing (zxing-android-embedded) ────────────────────────────────────────────
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ── App model classes used in JSON/Room serialisation ─────────────────────────
-keepclassmembers class dev.ringbridge.** {
    <fields>;
}
