pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // Reticulum-kt / LXMF-kt / LXST-kt + usb-serial-for-android
    }
}

rootProject.name = "columba"

// Opt-in composite-build override: point reticulum-kt/LXMF-kt/LXST-kt
// at a local checkout for a tight edit-build-install loop. Enable with
// e.g. `LOCAL_RETICULUM_KT=../reticulum-kt ./gradlew :app:installDebug`.
// Not committed as always-on to avoid masking the published artifact from
// CI / other developers.
System.getenv("LOCAL_RETICULUM_KT")?.let { includeBuild(it) }
System.getenv("LOCAL_LXMF_KT")?.let { includeBuild(it) }
System.getenv("LOCAL_LXST_KT")?.let { includeBuild(it) }

include(":app")
include(":data")
include(":domain")
include(":micron")
include(":reticulum")
include(":detekt-rules")
include(":screenshot-tests")
