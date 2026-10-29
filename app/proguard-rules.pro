# ==============================================================================
# VaultSync R8 / ProGuard Optimization & Obfuscation Rules
# ==============================================================================

# ── General & Stack Trace Diagnostics ─────────────────────────────────────────
# Preserve debugging attributes so production stack traces can be re-traced
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ── Kotlin & Coroutines ───────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { *; }
-keepclassmembers class kotlin.coroutines.** { *; }
-dontwarn kotlin.**

# Kotlin Coroutines internal reflection & dispatchers
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Android & Common Components ───────────────────────────────────────────────
# Keep Enums values and valueOf methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable CREATOR instances
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Jetpack Compose & Material 3 ──────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Room Database ─────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keep class **_Impl { *; }
-keep class com.vaultsync.core.database.** { *; }
-keep class com.vaultsync.core.trash.** { *; }
-dontwarn androidx.room.**

# ── Ktor CIO HTTP Server & Coroutine Sockets ───────────────────────────────────
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.ktor.**
-dontwarn io.netty.**
-dontwarn org.slf4j.**

# ── Coil 3 Image & Video Decoding ─────────────────────────────────────────────
-keep class coil3.** { *; }
-dontwarn coil3.**

# ── Media3 / ExoPlayer ────────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Jetpack Biometric ─────────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ── ZXing QR Code Generation ──────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── VaultSync Core & Application Components ───────────────────────────────────
# Application class
-keep class com.vaultsync.VaultSyncApplication { *; }

# Security, cryptography, and key management
-keep class com.vaultsync.core.security.** { *; }
-keepclassmembers class com.vaultsync.core.security.** { *; }

# Models and data classes
-keep class com.vaultsync.core.model.** { *; }
-keepclassmembers class com.vaultsync.core.model.** { *; }
-keep class com.vaultsync.core.manifest.** { *; }
-keep class com.vaultsync.core.migration.** { *; }

# Server & PC Access APIs
-keep class com.vaultsync.core.server.** { *; }
-keepclassmembers class com.vaultsync.core.server.** { *; }

# Stealth Mode, Icon Manager & AndroidManifest Components
-keep class com.vaultsync.MainActivity { *; }
-keep class com.vaultsync.core.stealth.** { *; }
-keep class com.vaultsync.feature.stealth.** { *; }
-keep class com.vaultsync.core.stealth.SecretCodeReceiver { *; }
-keep class com.vaultsync.core.server.PcAccessService { *; }
