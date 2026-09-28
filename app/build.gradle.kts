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
    // Paparazzi — opt-in via `-PenablePaparazzi=true` pour ne pas bloquer les builds courants
    // si l'AGP dépasse la compatibilité de la version Paparazzi pinée.
    // Les tests screenshot vivent dans app/src/test/java/com/tradingplatform/app/snapshots/.
    // Commande : ./gradlew recordPaparazziDebug -PenablePaparazzi=true pour régénérer.
    //            ./gradlew verifyPaparazziDebug -PenablePaparazzi=true en CI.
    alias(libs.plugins.paparazzi) apply false
}

if (project.findProperty("enablePaparazzi") == "true") {
    apply(plugin = "app.cash.paparazzi")
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
        // minSdk 28 (décision D1, CLAUDE.md §4) : sur API 26-27 BiometricPrompt passe par
        // FingerprintDialogFragment → AlertDialog AppCompat, qui plante avec le thème framework
        // actuel (android:Theme.Material.Light.NoActionBar). On exclut ces versions plutôt que
        // de basculer toute l'app sur un thème AppCompat.
        minSdk = 28
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

        // Gradle Managed Devices — job CI `instrumented` (.github/workflows/android.yml),
        // voir audit/plan-ui-tests-ci.md PART 2 §1. ATD (Automated Test Device) images :
        // pas de Play Store/GMS, démarrage plus rapide, comportement plus déterministe pour
        // du CI headless. api30 épingle #9 (Instant.parse pré-JDK12 sur un device réel plus
        // ancien) ; api34 couvre le FGS/VPN (foregroundServiceType="specialUse") et le chemin
        // BiometricPrompt actuel (minSdk 28, décision D1).
        managedDevices {
            localDevices {
                create("api30") {
                    device = "Pixel 5"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                }
                create("api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        // Pas de baseline pour l'instant : lint n'a jamais tourné sur ce module, donc aucun
        // fichier lint-baseline.xml existant a committer. AGP echoue le build si `baseline`
        // pointe vers un fichier absent (message "missing baseline file... will be created").
        // Quand l'audit aura fait tourner `./gradlew lintDebug` une premiere fois et trie les
        // faux positifs restants, decommenter la ligne suivante et committer le fichier genere :
        // baseline = file("lint-baseline.xml")
    }

}

// Les tests Paparazzi dépendent du plugin qui est opt-in via -PenablePaparazzi=true.
// Sans le plugin, les classes `app.cash.paparazzi.*` ne sont pas sur le classpath —
// on exclut le dossier snapshots/ de la compilation des tests pour ne pas casser le build.
if (project.findProperty("enablePaparazzi") != "true") {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name.contains("UnitTest", ignoreCase = true)) {
            exclude("**/snapshots/**")
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
    // FragmentActivity (MainActivity) — requis par BiometricPrompt
    implementation(libs.fragment.ktx)

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

    // Libsodium (chiffrement LAN).
    // JNA @aar obligatoire : le JAR standard (tiré transitivement par lazysodium-android) ne
    // contient que libjnidispatch pour Mac/Windows/Linux. Sans le AAR, SodiumAndroid.<init>
    // crash avec UnsatisfiedLinkError "libjnidispatch.so not found" sur arm64-v8a.
    // Exclure le JAR transitif pour éviter le conflit "Duplicate class com.sun.jna.*".
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.17.0@aar")

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
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    // Compose UI tests on the JVM (Robolectric) — e.g. BiometricLockOverlayTest
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.org.json)
    testImplementation(kotlin("test"))
    // kotlin-reflect — NOT already on the classpath (checked: absent from libs.versions.toml
    // and from every other module dependency). Needed by DtoContractTest (contracts/) for
    // KParameter.isOptional / .type.isMarkedNullable / .annotations on DTO primary
    // constructors — the same introspection Moshi's own KotlinJsonAdapterFactory performs at
    // runtime for non-codegen adapters.
    testImplementation(kotlin("reflect"))
    // JVM-only libsodium binding (JNA-backed) for SealedBoxHelperRealTest — 5.1.4 : 5.2.0 est compilé pour Java 21 (class v65) alors que les tests tournent sur JDK 17 — LazySodiumAndroid's
    // JNI .so cannot load on the plain JVM unit test runner. Same version as lazysodium-android
    // (libs.versions.toml "lazysodium") and the JNA version the main app already pulls in via
    // `implementation("net.java.dev.jna:jna:5.17.0@aar")` (NetworkModule / build.gradle.kts
    // above), to avoid resolving two different JNA versions on the test classpath.
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.test.ext.junit)
    // MockWebServer — SetupSmokeTest (MobileProvisioningRepositoryImpl réel, plain HTTP)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.compose.ui.test.manifest)
}
