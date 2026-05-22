# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Hilt classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Room entities
-keep class network.columba.app.data.local.entities.** { *; }

# Preserve attributes needed for debugging
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# ===== AIDL Interface Protection (CRITICAL) =====
# AIDL-generated classes must not be obfuscated or removed
# Without these rules, IPC between app and ReticulumService will fail
-keep class * implements android.os.IInterface { *; }
-keep class * extends android.os.Binder { *; }
-keep class network.columba.app.I** { *; }
-keepclassmembers class * implements android.os.IInterface {
    public *;
}

# ===== Service Protection =====
# ReticulumService runs in a separate process and uses IPC
-keep class network.columba.app.service.** { *; }
-keepclassmembers class network.columba.app.service.** { *; }

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

# ===== Reflectively-invoked bridge classes (Chaquopy / JNI-by-name) =====
# Classes invoked by name across a boundary R8 can't see (Python via Chaquopy,
# JNI) carry @network.columba.app.rns.api.annotation.ReflectivelyKept. Keeping
# them at class level preserves all current AND future members, so adding a
# method to a bridge needs no rule change. The ReflectivelyKeptRequired detekt
# rule enforces the annotation on Chaquopy bridge shapes (fun interfaces in
# :rns-backend-py + Kotlin*Bridge classes) so a new bridge can't merge
# unprotected.
#
# (Replaces the previous per-class androidx.annotation.Keep convention; the old
# `-keep class network.columba.app.reticulum.protocol.** { *; }` glob was
# already removed — it protected only test classes after a package rename.)
-keep @network.columba.app.rns.api.annotation.ReflectivelyKept class * { *; }

# ===== Chaquopy (Python runtime) =====
# Restored from release/v0.10.x: the com.lxmf.messenger -> network.columba.app
# rename dropped these rules, which regressed minified release builds — the
# Python backend fails at interpreter startup with an asset AssertionError
# (dumping assets/chaquopy/build.json). The -keepattributes below are the
# critical part: Chaquopy's reflection / PyObject.toJava() Java<->Python type
# bridging needs generic-signature and inner-class metadata that R8 strips by
# default, and that no -keep class rule restores.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
# Keep classes used with PyObject.toJava()
-keepclassmembers class * {
    *** toJava(...);
}

# ===== MessagePack Serialization =====
# MessagePack uses reflection to load buffer implementations
# Without these rules, LXMF message deserialization crashes
-keep class org.msgpack.** { *; }
-keepclassmembers class org.msgpack.** { *; }
-dontwarn org.msgpack.**

# ===== Java 16+ Unix Domain Sockets =====
# reticulum-kt's LocalClientInterface / LocalServerInterface reference
# java.net.UnixDomainSocketAddress for its local-IPC transport. The class
# is only available on Android API 31+; minSdk is 24 so R8 can't find it
# in the bootclasspath at link time. The code path is guarded by runtime
# API-level checks so it never executes on older devices; this just
# silences the build-time warning.
-dontwarn java.net.UnixDomainSocketAddress

# ===== ProGuard Debugging (Optional) =====
# Uncomment these to see what R8 is removing in build/outputs/mapping/release/
# -printconfiguration build/outputs/mapping/release/configuration.txt
# -printusage build/outputs/mapping/release/usage.txt
