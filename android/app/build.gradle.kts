plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.twinpad.android.app"
    // CTX-C1: Android mínimo 12 (API 31)
    compileSdk = 35

    lint {}

    defaultConfig {
        applicationId = "com.twinpad.android.app"
        minSdk = 31
        targetSdk = 35
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firma con clave debug SOLO para pruebas locales instalables;
            // la distribución pública exige keystore propio (ver TODO.md).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // UI clásica de Views (layouts XML + drawables XML, ENG §11: Google/AOSP
    // Apache-2.0); la vista editable se edita en Android Studio.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Revisión ENG §11: androidx.camera (Google, Apache-2.0, mantenidos)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // Decodificador QR (SPEC-002 §6 U2): ML Kit barcode (Google, Apache-2.0)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // NDJSON del protocolo (SPEC-001 §4.2)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
