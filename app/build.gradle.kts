import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.tradingplatform.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tradingplatform.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "VPS_BASE_URL",
            "\"${localProperties.getProperty("VPS_BASE_URL", "https://10.42.0.1:443")}\"")
        buildConfigField("String", "CERT_PIN_SHA256",
            "\"${localProperties.getProperty("CERT_PIN_SHA256", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")}\"")
        buildConfigField("String", "CERT_PIN_SHA256_BACKUP",
            "\"${localProperties.getProperty("CERT_PIN_SHA256_BACKUP", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")}\"")
        buildConfigField("String", "WG_VPS_ENDPOINT",
            "\"${localProperties.getProperty("WG_VPS_ENDPOINT", "vps.example.com:51820")}\"")
        buildConfigField("String", "WG_VPS_PUBKEY",
            "\"${localProperties.getProperty("WG_VPS_PUBKEY", "")}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        register("release") {
            storeFile = file(localProperties.getProperty("KEYSTORE_PATH", "../keystore/release.jks"))
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProperties.getProperty("KEY_ALIAS", "")
            keyPassword = localProperties.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "DEV_MODE", "false")
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEV_MODE", project.findProperty("DEV_MODE")?.toString() ?: "false")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4g"
                it.forkEvery = 1
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// ── Fail-fast: block release builds if DEV_MODE=true in local.properties ────
// Release buildType already hardcodes DEV_MODE=false in BuildConfig, but this
// guard catches any accidental change to that line or misconfigured CI pipeline.
tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) && name.startsWith("assemble")) {
        doFirst {
            val devMode = project.findProperty("DEV_MODE")?.toString()?.toBoolean() ?: false
            if (devMode) {
                throw GradleException(
                    "DEV_MODE=true is not allowed in release builds. " +
                    "Set DEV_MODE=false in local.properties before building release."
                )
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material.icons.ext)
    implementation(libs.compose.activity)
    implementation(libs.compose.lifecycle.runtime)
    implementation(libs.compose.viewmodel)
    implementation(libs.lifecycle.process)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.nav.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Navigation
    implementation(libs.navigation.compose)

    // Réseau
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.adapters)
    ksp(libs.moshi.kotlin.codegen)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore + Security
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // Biométrie
    implementation(libs.biometric)

    // Glance widgets
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // WorkManager
    implementation(libs.workmanager.ktx)

    // Caméra + QR
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)

    // WireGuard
    implementation(libs.wireguard.android)

    // Sécurité
    implementation(libs.rootbeer)

    // Libsodium (chiffrement LAN)
    implementation(libs.lazysodium.android)

    // Utilitaires
    implementation(libs.timber)
    debugImplementation(libs.leakcanary)

    // Coroutines
    implementation(libs.coroutines.android)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    testImplementation(kotlin("test"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
