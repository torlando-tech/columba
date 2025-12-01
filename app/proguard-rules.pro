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

# Keep Chaquopy Python infrastructure
-keep class com.chaquo.python.** { *; }
-keepclassmembers class com.chaquo.python.** { *; }

# Preserve attributes needed for Python reflection and debugging
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Don't warn about Chaquopy's internal dependencies
-dontwarn com.chaquo.python.**

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

# ===== Chaquopy Reflection Support =====
# PyObject.toJava() uses reflection and needs complete type information
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep classes used with PyObject.toJava()
-keepclassmembers class * {
    *** toJava(...);
}

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

# ===== BLE Bridge =====
# KotlinBLEBridge is called from Python via Chaquopy
# Python expects specific method names that must not be obfuscated
-keep class com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge { *; }
-keepclassmembers class com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge { *; }

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
