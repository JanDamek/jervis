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
        // JOGL/GlueGen for compose-webview-multiplatform → kcef → jcef
        maven("https://jogamp.org/deployment/maven/")
    }
}

rootProject.name = "jervis"

if (System.getenv("DOCKER_BUILD") != "true") {
    include(
        ":shared:common-dto",
        ":shared:common-api",
        ":shared:domain",
        ":shared:service-contracts",
        ":shared:ui-common",
    )
} else {
    include(
        ":shared:common-dto",
        ":shared:common-api",
        ":shared:domain",
        ":shared:service-contracts",
    )
}

if (System.getenv("DOCKER_BUILD") != "true") {
    include(
        ":apps:androidApp",
        ":apps:androidApp:androidWatchApp",
        ":apps:desktop",
        ":apps:iosApp",
    )
    include(
        ":backend:service-atlassian",
        ":backend:service-gitlab",
        ":backend:service-coding-engine",
        ":backend:common-services",
        ":backend:server",
        ":backend:service-claude",
        ":backend:service-github",
        ":backend:service-o365-gateway",
        ":backend:service-ollama-router",
    )
}
