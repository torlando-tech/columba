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
        mavenLocal() // TEMP: consume reticulum-kt fix/announce-hop-diagnostics (0.0.6-hops-diag)
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // Reticulum-kt / LXMF-kt / LXST-kt + usb-serial-for-android
    }
}

rootProject.name = "columba"
include(":app")
include(":data")
include(":domain")
include(":micron")
include(":reticulum")
include(":detekt-rules")
include(":screenshot-tests")
