# Gradle Setup — Version Catalog

Fichier de reference : `gradle/libs.versions.toml`.

---

```toml
[versions]
kotlin              = "2.2.20"
agp                 = "9.0.1"
ksp                 = "2.3.6"
desugar-jdk-libs    = "2.1.5"

compose-bom         = "2026.03.00"
activity-compose    = "1.13.0"
lifecycle           = "2.10.0"

hilt                = "2.58"
hilt-androidx       = "1.3.0"

retrofit            = "2.11.0"
okhttp              = "4.12.0"
moshi               = "1.15.2"

room                = "2.8.4"
datastore           = "1.2.1"
security-crypto     = "1.1.0"

biometric           = "1.2.0-alpha05"   # ⚠ alpha — ne pas upgrader sans tester

workmanager         = "2.11.1"
glance              = "1.1.1"
navigation          = "2.9.0"
coroutines          = "1.10.2"

camerax             = "1.5.3"
mlkit-barcode       = "17.3.0"

rootbeer            = "0.1.2"
wireguard           = "1.0.20250531"
lazysodium          = "5.2.0"

firebase-bom        = "34.0.0"
owasp-depcheck      = "10.0.4"
leakcanary          = "2.14"
timber              = "5.0.1"
org-json            = "20251224"

# Test
turbine             = "1.2.1"
mockk               = "1.14.2"
junit               = "4.13.2"
compose-test-bom    = "2026.03.00"
robolectric         = "4.14.1"
test-core           = "1.7.0"

[libraries]
# ── Compose ──────────────────────────────────────────────────────────────────
compose-bom                 = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui                  = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview  = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling          = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-material3           = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons      = { group = "androidx.compose.material", name = "material-icons-core" }
compose-material-icons-ext  = { group = "androidx.compose.material", name = "material-icons-extended" }
compose-activity            = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }
compose-lifecycle-runtime   = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
compose-viewmodel           = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-process           = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }

# ── Hilt ─────────────────────────────────────────────────────────────────────
hilt-android                = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler               = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-nav-compose            = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hilt-androidx" }
hilt-work                   = { group = "androidx.hilt", name = "hilt-work", version.ref = "hilt-androidx" }
hilt-work-compiler          = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hilt-androidx" }

# ── Navigation ────────────────────────────────────────────────────────────────
navigation-compose          = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }

# ── Reseau ────────────────────────────────────────────────────────────────────
# OkHttp 4.12.x stable — ne pas utiliser 5.x alpha en production financiere
retrofit                    = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-moshi              = { group = "com.squareup.retrofit2", name = "converter-moshi", version.ref = "retrofit" }
okhttp                      = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging              = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
# Moshi — respecte la nullabilite Kotlin, sur pour BigDecimal depuis strings
# moshi-kotlin (reflexion) intentionnellement absent — redondant avec codegen et alourdit l'APK
# Utiliser uniquement moshi (core) + codegen (@JsonClass(generateAdapter = true) sur chaque DTO)
moshi                       = { group = "com.squareup.moshi", name = "moshi", version.ref = "moshi" }
moshi-adapters              = { group = "com.squareup.moshi", name = "moshi-adapters", version.ref = "moshi" }
moshi-kotlin-codegen        = { group = "com.squareup.moshi", name = "moshi-kotlin-codegen", version.ref = "moshi" }

# ── Room ──────────────────────────────────────────────────────────────────────
room-runtime                = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx                    = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler               = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# ── Stockage securise ─────────────────────────────────────────────────────────
# DataStore standard + security-crypto pour chiffrement avec MasterKey (Keystore AES-256-GCM)
datastore-preferences       = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
security-crypto             = { group = "androidx.security", name = "security-crypto-ktx", version.ref = "security-crypto" }

# ── Biometrie ─────────────────────────────────────────────────────────────────
biometric                   = { group = "androidx.biometric", name = "biometric-ktx", version.ref = "biometric" }

# ── Glance (widgets) ──────────────────────────────────────────────────────────
glance-appwidget            = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
glance-material3            = { group = "androidx.glance", name = "glance-material3", version.ref = "glance" }

# ── WorkManager ───────────────────────────────────────────────────────────────
workmanager-ktx             = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workmanager" }

# ── Camera + QR (scan pairing) ────────────────────────────────────────────────
camerax-core                = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camerax-camera2             = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle           = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-view                = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
mlkit-barcode               = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkit-barcode" }

# ── Firebase / FCM ────────────────────────────────────────────────────────────
firebase-bom                = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebase-bom" }
firebase-messaging          = { group = "com.google.firebase", name = "firebase-messaging" }          # version via BOM (ktx merged in BOM 33+)
firebase-crashlytics        = { group = "com.google.firebase", name = "firebase-crashlytics" }         # version via BOM (ktx merged in BOM 33+)

# ── Desugaring ────────────────────────────────────────────────────────────────
desugar-jdk-libs            = { group = "com.android.tools", name = "desugar_jdk_libs", version.ref = "desugar-jdk-libs" }

# ── WireGuard ─────────────────────────────────────────────────────────────────
# Bibliotheque officielle WireGuard (BoringTun userspace + GoBackend)
wireguard-android           = { group = "com.wireguard.android", name = "tunnel", version.ref = "wireguard" }

# ── Securite ──────────────────────────────────────────────────────────────────
rootbeer                    = { group = "com.scottyab", name = "rootbeer-lib", version.ref = "rootbeer" }
lazysodium-android          = { group = "com.goterl", name = "lazysodium-android", version.ref = "lazysodium" }

# ── Tests ─────────────────────────────────────────────────────────────────────
org-json                    = { group = "org.json", name = "json", version.ref = "org-json" }
junit                       = { group = "junit", name = "junit", version.ref = "junit" }
mockk                       = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine                     = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
coroutines-android          = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test             = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
compose-ui-test             = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest    = { group = "androidx.compose.ui", name = "ui-test-manifest" }
# WorkManager testing — TestListenableWorkerBuilder pour WidgetUpdateWorker
work-testing                = { group = "androidx.work", name = "work-testing", version.ref = "workmanager" }
# Robolectric — Android unit tests sur JVM (requis pour ApplicationProvider, Context)
robolectric                 = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
test-core                   = { group = "androidx.test", name = "core", version.ref = "test-core" }
# MockWebServer — tests intercepteurs OkHttp (CsrfInterceptor, TokenAuthenticator, VpnRequiredInterceptor)
okhttp-mockwebserver        = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
# LeakCanary — debug uniquement (detection fuites memoire ViewModels, services, coroutines)
leakcanary                  = { group = "com.squareup.leakcanary", name = "leakcanary-android", version.ref = "leakcanary" }
# Timber — logging structure avec strip automatique en release via ProGuard
timber                      = { group = "com.jakewharton.timber", name = "timber", version.ref = "timber" }

[plugins]
android-application         = { id = "com.android.application", version.ref = "agp" }
kotlin-android              = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose              = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt                        = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp                         = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room                        = { id = "androidx.room", version.ref = "room" }
google-services             = { id = "com.google.gms.google-services", version = "4.4.4" }
firebase-crashlytics        = { id = "com.google.firebase.crashlytics", version = "3.0.6" }
owasp-depcheck              = { id = "org.owasp.dependencycheck", version.ref = "owasp-depcheck" }
```

---

## Notes importantes

### Compatibilite KSP / Kotlin
La version KSP (`ksp = "2.3.6"`) doit correspondre **exactement** a la version
Kotlin. Format : `{kotlin_version}-{ksp_patch}`. Verifier a chaque upgrade Kotlin.

### Room + KSP
Utiliser KSP (pas KAPT) pour Room :
```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}
dependencies {
    ksp(libs.room.compiler)
}
room { schemaDirectory("$projectDir/schemas") }
```

### Hilt + KSP
```kotlin
plugins { alias(libs.plugins.hilt) }
dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)   // si WorkManager + Hilt
}
```

### Compose BOM
Toutes les dependances Compose (ui, material3, ui-tooling...) doivent utiliser le BOM
sans preciser de version individuelle :
```kotlin
implementation(platform(libs.compose.bom))
implementation(libs.compose.ui)        // pas de version.ref ici
implementation(libs.compose.material3)
```

### Lazysodium — chiffrement LAN (crypto_box_seal)

`lazysodium-android` est un wrapper JNA autour de libsodium, utilise par `SealedBoxHelper`
pour chiffrer les payloads LAN (pairing + maintenance). La bibliotheque inclut des fichiers
`.so` natifs pour chaque ABI.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(libs.lazysodium.android)
}

android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")  // exclure x86/x86_64 pour reduire l'APK
        }
    }
}
```

### Desugaring — API java.time sur API < 26

`desugar_jdk_libs` permet d'utiliser `java.time.Instant`, `java.time.Duration` etc. sur
les appareils pre-API 26 (Android 7 et inferieur) :

```kotlin
// app/build.gradle.kts
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
```

### LeakCanary + Timber

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation(libs.leakcanary)   // detection fuites — debug seulement, jamais release
    implementation(libs.timber)
}
```

Initialiser Timber dans `Application.onCreate()` :
```kotlin
if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
// En release : planter un arbre qui reporte vers Crashlytics
else Timber.plant(CrashlyticsTree())
```

Remplacer tous les `Log.d/e/w()` par `Timber.d/e/w()`. En release, ProGuard strip les appels
`Timber.d()` automatiquement si configure (ajouter `-assumenosideeffects` pour Timber).

### Moshi + KSP (remplace Gson)
Moshi respecte la nullabilite Kotlin — safe pour les `BigDecimal` financiers deserialises
depuis strings. Gson peut injecter `null` dans un champ non-nullable via reflexion.

`moshi-kotlin` (reflexion) est **intentionnellement absent** — il est redondant avec le codegen
et ajoute du poids APK + du temps de startup. Utiliser uniquement `moshi` (core) + codegen.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(libs.moshi)              // core uniquement — pas moshi-kotlin
    implementation(libs.moshi.adapters)
    ksp(libs.moshi.kotlin.codegen)          // genere les adapters a la compilation
    implementation(libs.retrofit.moshi)
}
```

Annoter les data classes DTOs avec `@JsonClass(generateAdapter = true)` et les champs avec
`@Json(name = "field_name")`.

### Firebase / FCM

Les alertes arrivent via FCM. Utiliser le BOM Firebase pour eviter les conflits.
Depuis le BOM 33+, les artefacts `-ktx` sont merges dans les artefacts principaux —
utiliser `firebase-messaging` (sans `-ktx`) :

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
}
```

Ajouter le plugin `com.google.gms.google-services` dans `plugins {}` et inclure
`google-services.json` dans `app/` (non commite — ajouter a `.gitignore`).

### OkHttp — pourquoi 4.12.x et pas 5.x
OkHttp 5.x est en alpha depuis 2022. Pour une application financiere en production, utiliser
4.12.x stable. Reconsiderer l'upgrade vers 5.x uniquement apres sa release stable officielle.

### buildConfig — activation obligatoire (AGP 8.x+)

Depuis AGP 8.x, la generation de `BuildConfig` est opt-in. Sans cette declaration,
`BuildConfig.VPS_BASE_URL`, `BuildConfig.CERT_PIN_SHA256` etc. ne compilent pas :

```kotlin
// app/build.gradle.kts
android {
    buildFeatures {
        buildConfig = true   // obligatoire pour acceder aux BuildConfig fields
        compose = true
    }
    defaultConfig {
        buildConfigField("String", "VPS_BASE_URL", "\"${localProperties["VPS_BASE_URL"]}\"")
        buildConfigField("String", "CERT_PIN_SHA256", "\"${localProperties["CERT_PIN_SHA256"]}\"")
        buildConfigField("String", "CERT_PIN_SHA256_BACKUP", "\"${localProperties["CERT_PIN_SHA256_BACKUP"]}\"")
        buildConfigField("String", "WG_VPS_ENDPOINT", "\"${localProperties["WG_VPS_ENDPOINT"]}\"")
        buildConfigField("String", "WG_VPS_PUBKEY", "\"${localProperties["WG_VPS_PUBKEY"]}\"")
    }
}
```

### OWASP Dependency Check

Le plugin doit etre declare dans `build.gradle.kts` pour que `./gradlew dependencyCheckAnalyze`
fonctionne :

```kotlin
// build.gradle.kts (root)
plugins {
    alias(libs.plugins.owasp.depcheck)
}
dependencyCheck {
    failBuildOnCVSS = 7.0f          // fail sur CVSS >= 7 (high/critical)
    suppressionFile = "dependency-check-suppressions.xml"
}
```

### Tests — dependances a ajouter dans app/build.gradle.kts

```kotlin
dependencies {
    // JVM unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.org.json)            // org.json pour ParseVpsQrUseCaseTest etc.
    testImplementation(libs.robolectric)          // Android unit tests sur JVM
    testImplementation(libs.test.core)            // ApplicationProvider, Context
    // WorkManager
    testImplementation(libs.work.testing)
    // MockWebServer — intercepteurs OkHttp
    testImplementation(libs.okhttp.mockwebserver)
    // Compose UI tests
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

### CI/CD — secrets non commites

Fichiers a injecter via variables d'environnement en CI :
- `local.properties` -> encoder en base64, decoder en step CI avant le build
- `google-services.json` -> stocker comme secret CI, ecrire dans `app/` au runtime
- `keystore/*.jks` -> meme approche base64

```yaml
# Exemple GitHub Actions
- name: Decode local.properties
  run: echo "${{ secrets.LOCAL_PROPERTIES_B64 }}" | base64 -d > local.properties
- name: Decode google-services.json
  run: echo "${{ secrets.GOOGLE_SERVICES_B64 }}" | base64 -d > app/google-services.json
```
