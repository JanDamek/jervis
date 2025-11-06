pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
        maven("https://repo.spring.io/milestone")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://jitpack.io")
    }
}

rootProject.name = "jervis"

// Include common-dto as a composite build (KMP project) - only if directory exists
// In Docker, common-dto is pre-built to mavenLocal before this stage
val commonDtoDir = file("common-dto")
if (commonDtoDir.exists() && commonDtoDir.isDirectory) {
    includeBuild("common-dto") {
        dependencySubstitution {
            substitute(module("com.jervis:common-dto")).using(project(":"))
        }
    }
}

include(
    ":common-api",
    ":server",
    ":desktop",
    ":common-services",
    ":service-tika",
    ":service-joern",
    ":service-whisper",
)
