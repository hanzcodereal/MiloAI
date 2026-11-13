plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.miloai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.miloai"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Penandatanganan rilis: pakai keystore sendiri bila variabel lingkungan diisi
    // (MILO_KEYSTORE, MILO_KEYSTORE_PASSWORD, MILO_KEY_ALIAS, MILO_KEY_PASSWORD).
    // Jika tidak, APK release ditandatangani dengan kunci debug agar tetap bisa dipasang.
    val miloKeystore = System.getenv("MILO_KEYSTORE")
    signingConfigs {
        if (!miloKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(miloKeystore)
                storePassword = System.getenv("MILO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MILO_KEY_ALIAS")
                keyPassword = System.getenv("MILO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (!miloKeystore.isNullOrBlank()) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            // Versi lite: kecilkan kode (R8) dan buang resource yang tidak terpakai
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }


    buildFeatures {
        compose = true
    }

    // Hanya simpan resource bahasa Indonesia (termasuk dari library) agar APK lebih kecil
    androidResources {
        localeFilters += listOf("in")
    }

    packaging {
        resources {
            excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":providers"))
    implementation(project(":skills"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.markdown.renderer)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
