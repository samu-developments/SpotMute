plugins {
    `version-catalog`
    alias(libs.plugins.spotless)
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.android.build.tools.gradle)
        classpath(libs.gradle.plugin)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    apply(plugin = "com.diffplug.spotless")

    spotless {
        kotlin {
            target("**/*.kt")
            ktlint(libs.versions.ktlint.get())
        }
        kotlinGradle {
            ktlint(libs.versions.ktlint.get())
        }
    }
}
