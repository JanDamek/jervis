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

// Shared modules - skip ui-common in Docker build (requires Compose plugin)
if (System.getenv("DOCKER_BUILD") != "true") {
    include(
        ":shared:common-api",
        ":shared:domain",
        ":shared:ui-common"
    )
} else {
    include(
        ":shared:common-api",
        ":shared:domain"
    )
}

// Backend modules (JVM-only)
include(
    ":backend:common-services",
    ":backend:server",
    ":backend:service-tika",
    ":backend:service-joern",
    ":backend:service-whisper"
)

// Application launchers - skip in Docker build
if (System.getenv("DOCKER_BUILD") != "true") {
    include(
        ":apps:desktop",
        ":apps:mobile"
    )
}
