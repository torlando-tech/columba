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
// LXMF-kt + LXST-kt are published on JitPack with its single-module
// root-coord collapse: the Maven coord is `<user>:<repo>`, but the
// actual Gradle module is a subproject (lxmf-core / lxst-core). A
// plain `includeBuild` won't auto-substitute because the project
// group (`com.github.torlando-tech.LXMF-kt`) and artifact
// (`lxmf-core`) don't match the consumer-side coord
// (`com.github.torlando-tech:LXMF-kt`). We add an explicit
// dependencySubstitution to map the collapsed coord to the real
// subproject.
System.getenv("LOCAL_LXMF_KT")?.let {
    includeBuild(it) {
        dependencySubstitution {
            substitute(module("com.github.torlando-tech:LXMF-kt"))
                .using(project(":lxmf-core"))
        }
    }
}
System.getenv("LOCAL_LXST_KT")?.let {
    includeBuild(it) {
        dependencySubstitution {
            substitute(module("com.github.torlando-tech:LXST-kt"))
                .using(project(":lxst-core"))
        }
    }
}

include(":app")
include(":data")
include(":domain")
include(":micron")
include(":reticulum")
include(":detekt-rules")
include(":screenshot-tests")
