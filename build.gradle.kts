plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.ideaser"
version = "0.2.3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    // China mirror first for faster resolution
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compile against the locally installed IDEA CE — complete and identical
        // to the IDE the plugin will actually run in
        local("/Applications/IntelliJ IDEA CE.app/Contents")

        // YAML PSI used to parse application*.yml / bootstrap*.yml
        bundledPlugin("org.jetbrains.plugins.yaml")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // no untilBuild: stay compatible with future IDE versions
            untilBuild = provider { null }
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
