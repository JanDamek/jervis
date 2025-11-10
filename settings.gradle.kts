pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://repo.spring.io/milestone")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        mavenLocal()
        google()
        maven("https://repo.spring.io/milestone")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://jitpack.io")
    }
}

rootProject.name = "jervis"

// Shared modules (KMP) - common-dto as composite build for plugin isolation
includeBuild("shared/common-dto") {
    dependencySubstitution {
        substitute(module("com.jervis:common-dto")).using(project(":"))
    }
}

include(
    ":shared:common-api",
    ":shared:domain",
    ":shared:ui-common"
)

// Backend modules (JVM-only)
include(
    ":backend:common-services",
    ":backend:server",
    ":backend:service-tika",
    ":backend:service-joern",
    ":backend:service-whisper"
)

// Application launchers
include(
    ":apps:desktop",
    ":apps:mobile"
)
