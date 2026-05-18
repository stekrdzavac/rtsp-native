pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rtsp-native"

include(":rtspcore")
include(":rtspsignaling")
include(":rtsptransport")
include(":h264depacketizer")
include(":h265depacketizer")
include(":clocksync")
include(":videodecoder")
include(":videorendering")
include(":rtspkit")
include(":sample")
