# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Hilt classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Reticulum model classes
-keep class com.lxmf.messenger.reticulum.model.** { *; }

# Keep Room entities
-keep class com.lxmf.messenger.data.local.entities.** { *; }

# Preserve attributes needed for debugging
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# ===== AIDL Interface Protection (CRITICAL) =====
# AIDL-generated classes must not be obfuscated or removed
# Without these rules, IPC between app and ReticulumService will fail
-keep class * implements android.os.IInterface { *; }
-keep class * extends android.os.Binder { *; }
-keep class com.lxmf.messenger.I** { *; }
-keepclassmembers class * implements android.os.IInterface {
    public *;
}

# ===== Service Protection =====
# ReticulumService runs in a separate process and uses IPC
-keep class com.lxmf.messenger.service.** { *; }
-keepclassmembers class com.lxmf.messenger.service.** { *; }

# ===== Android IPC Components =====
-keep class android.os.RemoteCallbackList { *; }
-keep class android.os.IBinder { *; }

# ===== Native Methods (JNI) =====
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ===== Kotlin Coroutines =====
# Used extensively in service for async operations
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ===== Reticulum Protocol Bridge Classes =====
# These classes bridge between Kotlin and Python
-keep class com.lxmf.messenger.reticulum.protocol.** { *; }

# ===== MessagePack Serialization =====
# MessagePack uses reflection to load buffer implementations
# Without these rules, LXMF message deserialization crashes
-keep class org.msgpack.** { *; }
-keepclassmembers class org.msgpack.** { *; }
-dontwarn org.msgpack.**

# ===== ProGuard Debugging (Optional) =====
# Uncomment these to see what R8 is removing in build/outputs/mapping/release/
# -printconfiguration build/outputs/mapping/release/configuration.txt
# -printusage build/outputs/mapping/release/usage.txt
