pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Changed from FAIL_ON_PROJECT_REPOS to PREFER_SETTINGS to allow codec2_talkie submodule
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // For usb-serial-for-android
    }
}

rootProject.name = "columba"
include(":app")
include(":data")
include(":domain")
include(":reticulum")
include(":detekt-rules")
include(":screenshot-tests")
include(":external:codec2_talkie:libcodec2-android")
