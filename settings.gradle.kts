pluginManagement {
    repositories {
        // China mirrors first for faster resolution
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Allow Gradle to auto-provision the JDK 21 toolchain if it is missing locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "spring-cloud-port-viewer"
