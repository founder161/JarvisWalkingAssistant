import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun sp(name: String): String? = System.getenv(name) ?: signingProps.getProperty(name)

android {
    namespace = "com.example.jarviswalkingassistant.wear"
    compileSdk = 35

    defaultConfig {
        // MUST match the phone app id, or the watch and phone can't talk.
        applicationId = "com.example.jarviswalkingassistant"
        minSdk = 30
        targetSdk = 34
        versionCode = 33
        versionName = "3.3"
    }
    signingConfigs {
        create("jarvis") {
            val ks = sp("JARVIS_KEYSTORE")
            if (ks != null && rootProject.file(ks).exists()) {
                storeFile = rootProject.file(ks)
                storePassword = sp("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = sp("JARVIS_KEY_ALIAS") ?: "jarvis"
                keyPassword = sp("JARVIS_KEY_PASSWORD") ?: sp("JARVIS_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis").takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    sourceSets {
        getByName("main") {
            java.srcDirs("../shared/src/main/java")
            assets.srcDirs("../shared/src/main/assets")
        }
    }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES") }
}

dependencies {
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
