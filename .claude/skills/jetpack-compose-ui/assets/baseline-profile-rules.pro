# Jetpack Compose ProGuard/R8 Rules
# Last Updated: October 2025
# For Compose BOM 2025.10.01, Material 3 1.4.0

# ==============================================================================
# COMPOSE COMPILER - Essential Rules
# ==============================================================================

# Keep Compose runtime annotations
-keep @androidx.compose.runtime.Composable class * { *; }
-keep @androidx.compose.runtime.Stable class * { *; }
-keep @androidx.compose.runtime.Immutable class * { *; }

# Keep ComposableLambda classes
-keep class androidx.compose.runtime.internal.ComposableLambdaImpl { *; }
-keep class androidx.compose.runtime.ComposableLambda { *; }

# ==============================================================================
# MATERIAL 3 - Component Rules
# ==============================================================================

# Keep Material 3 components
-keep class androidx.compose.material3.** { *; }
-keep interface androidx.compose.material3.** { *; }

# Keep Material 3 tokens (used for theming)
-keep class androidx.compose.material3.tokens.** { *; }

# ==============================================================================
# COMPOSE UI - Core Rules
# ==============================================================================

# Keep Compose UI classes
-keep class androidx.compose.ui.** { *; }
-keep interface androidx.compose.ui.** { *; }

# Keep Modifier classes
-keep class androidx.compose.ui.Modifier { *; }
-keep class androidx.compose.ui.Modifier$** { *; }

# Keep layout classes
-keep class androidx.compose.foundation.layout.** { *; }

# ==============================================================================
# LIFECYCLE - ViewModel and StateFlow
# ==============================================================================

# Keep ViewModel classes
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# Keep StateFlow and SharedFlow
-keep class kotlinx.coroutines.flow.StateFlow { *; }
-keep class kotlinx.coroutines.flow.SharedFlow { *; }
-keep class kotlinx.coroutines.flow.MutableStateFlow { *; }
-keep class kotlinx.coroutines.flow.MutableSharedFlow { *; }

# ==============================================================================
# NAVIGATION COMPOSE - If Using
# ==============================================================================

# Keep navigation arguments
-keep class androidx.navigation.** { *; }
-keepclassmembers class * {
    @androidx.navigation.** <fields>;
}

# Keep NavHost and NavController
-keep class androidx.navigation.compose.** { *; }

# ==============================================================================
# HILT - If Using
# ==============================================================================

# Keep @HiltViewModel annotated classes
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Keep @Inject constructors
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# ==============================================================================
# COROUTINES - Essential for Compose
# ==============================================================================

# Keep coroutine classes
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }

# Keep CoroutineContext
-keep class kotlin.coroutines.CoroutineContext { *; }

# ==============================================================================
# SERIALIZATION - If Using kotlinx.serialization
# ==============================================================================

# Keep @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep @kotlinx.serialization.Serializable class * {
    *;
}

# ==============================================================================
# DATA CLASSES - Keep for Compose State
# ==============================================================================

# Keep data class properties (used in Compose state)
-keepclassmembers class * {
    kotlin.Metadata <fields>;
}

# Keep data class copy methods
-keepclassmembers class * {
    public ** copy(...);
}

# Keep data class component methods (for destructuring)
-keepclassmembers class * {
    public ** component1();
    public ** component2();
    public ** component3();
    public ** component4();
    public ** component5();
}

# ==============================================================================
# COIL - If Using for Image Loading
# ==============================================================================

-keep class coil.** { *; }
-keep interface coil.** { *; }

# ==============================================================================
# PERFORMANCE OPTIMIZATION
# ==============================================================================

# Enable optimization (R8)
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Enable aggressive optimization
-allowaccessmodification
-mergeinterfacesaggressively

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ==============================================================================
# REFLECTION - Keep for Compose Runtime
# ==============================================================================

# Keep reflection annotations
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations

# Keep source file and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable

# Keep inner classes
-keepattributes InnerClasses,EnclosingMethod

# Keep generic signatures
-keepattributes Signature

# ==============================================================================
# BASELINE PROFILES - For Startup Performance
# ==============================================================================

# Baseline profiles are automatically included by R8/AGP
# No special rules needed, but ensure:
# 1. androidx.profileinstaller:profileinstaller:1.3.1+ dependency added
# 2. Default baseline profiles from Compose libraries are used
# 3. Custom baseline profiles placed in src/main/baseline-prof.txt

# ==============================================================================
# CUSTOM APP-SPECIFIC RULES
# ==============================================================================

# Keep your app's data classes used in Compose
-keep class com.example.yourapp.data.** { *; }
-keep class com.example.yourapp.ui.**.UiState { *; }
-keep class com.example.yourapp.ui.**.Action { *; }
-keep class com.example.yourapp.ui.**.Event { *; }

# Keep your @Stable and @Immutable classes
-keep @androidx.compose.runtime.Stable class com.example.yourapp.** { *; }
-keep @androidx.compose.runtime.Immutable class com.example.yourapp.** { *; }

# ==============================================================================
# TROUBLESHOOTING
# ==============================================================================

# If seeing ClassNotFoundException at runtime:
# 1. Add -keep rules for the missing class
# 2. Check -printconfiguration output
# 3. Use -whyareyoukeeping to debug

# If Compose components are missing:
# Ensure R8 version is 3.3.28+ (AGP 7.4+)
# Compose requires modern R8 for proper optimization

# If ViewModel is null:
# Ensure -keep rules for ViewModel and SavedStateHandle

# ==============================================================================
# VERIFICATION
# ==============================================================================

# After building release:
# 1. Check build/outputs/mapping/release/usage.txt
# 2. Verify critical classes are kept
# 3. Test app thoroughly in release mode
# 4. Check for NoSuchMethodError or ClassNotFoundException

# Generate configuration:
# -printconfiguration build/outputs/mapping/release/configuration.txt
