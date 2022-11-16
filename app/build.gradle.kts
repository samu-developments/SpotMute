import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.developments.samu.muteforspotify"
    compileSdk = 33
    defaultConfig {
        applicationId = "com.developments.samu.muteforspotify"
        minSdk = 23
        targetSdk = 33
        versionCode = 43
        versionName = "2.0.3"
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    applicationVariants.all {
        val variant = this
        this.outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "${variant.name}-${variant.versionName}.apk"
        }
    }

    lint {
        disable += "MissingTranslation"
    }

    buildToolsVersion = "31.0.0"
}

dependencies {
    implementation(fileTree("dir" to "libs", "include" to listOf("*.jar")))
    implementation(libs.kotlin.stdlib.jdk7)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.google.android.material)
    implementation(libs.google.android.play.core.ktx)
    implementation(libs.jetbrains.kotlinx.couroutines.android)

    implementation(libs.doubledot.doki) {
        isTransitive = true
    }
    implementation(libs.threetenabp)
}
