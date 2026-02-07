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
        maven { url = uri("https://jitpack.io") } // For usb-serial-for-android
    }
}

rootProject.name = "columba"
include(":app")
include(":data")
include(":domain")
include(":lxst")
include(":reticulum")
include(":detekt-rules")
include(":screenshot-tests")
