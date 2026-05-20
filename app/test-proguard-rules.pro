# Test-APK-only ProGuard/R8 config.
#
# Applied via `testProguardFiles(...)` on the `releaseMinified` build type. When
# the target app build type is minified, AGP also runs R8 over the androidTest
# APK — which would strip/rename the instrumentation runner and our @Test classes
# and break test discovery. We don't ship the test APK, so just leave it intact.
#
# This does NOT relax the target app APK — that one stays fully minified/obfuscated,
# which is exactly what PythonKotlinBridgeR8Test asserts against.
-dontshrink
-dontobfuscate
-dontoptimize
