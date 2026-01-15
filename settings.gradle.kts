pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://repo.spring.io/milestone")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-rpc/maven")
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

if (System.getenv("DOCKER_BUILD") != "true") {
    include(
        ":shared:common-dto",
        ":shared:common-api",
        ":shared:domain",
        ":shared:ui-common",
    )
} else {
    include(
        ":shared:common-dto",
        ":shared:common-api",
        ":shared:domain",
    )
}

include(
    ":backend:common-services",
    ":backend:server",
    ":backend:service-tika",
    ":backend:service-joern",
    ":backend:service-whisper",
    ":backend:service-aider",
    ":backend:service-coding-engine",
    ":backend:service-atlassian",
    ":backend:service-junie",
)

if (System.getenv("DOCKER_BUILD") != "true") {
    include(
        ":apps:desktop",
        ":apps:mobile",
    )
}
