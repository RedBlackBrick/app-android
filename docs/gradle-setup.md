# Gradle Setup — Version Catalog

Fichier à placer dans `gradle/libs.versions.toml`.

---

```toml
[versions]
kotlin              = "2.0.21"
agp                 = "8.7.3"
ksp                 = "2.0.21-1.0.28"

compose-bom         = "2025.01.00"
activity-compose    = "1.10.0"
lifecycle           = "2.8.7"

hilt                = "2.52"
hilt-androidx       = "1.2.0"

retrofit            = "2.11.0"
okhttp              = "5.0.0-alpha.14"

room                = "2.7.0"
datastore           = "1.1.2"
security-crypto     = "1.1.0-alpha06"   # ⚠ alpha — ne pas upgrader sans tester

biometric           = "1.2.0-alpha05"   # ⚠ alpha — ne pas upgrader sans tester

workmanager         = "2.10.0"
glance              = "1.1.1"
navigation          = "2.8.5"
coroutines          = "1.9.0"

camerax             = "1.4.1"
mlkit-barcode       = "17.3.0"

rootbeer            = "0.1.0"

# Test
turbine             = "1.2.0"
mockk               = "1.13.13"
junit               = "4.13.2"
compose-test-bom    = "2025.01.00"

[libraries]
# ── Compose ──────────────────────────────────────────────────────────────────
compose-bom                 = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui                  = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview  = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling          = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-material3           = { group = "androidx.compose.material3", name = "material3" }
compose-activity            = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }
compose-lifecycle-runtime   = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
compose-viewmodel           = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }

# ── Hilt ─────────────────────────────────────────────────────────────────────
hilt-android                = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler               = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-nav-compose            = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hilt-androidx" }
hilt-work                   = { group = "androidx.hilt", name = "hilt-work", version.ref = "hilt-androidx" }
hilt-work-compiler          = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hilt-androidx" }

# ── Navigation ────────────────────────────────────────────────────────────────
navigation-compose          = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }

# ── Réseau ────────────────────────────────────────────────────────────────────
retrofit                    = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-gson               = { group = "com.squareup.retrofit2", name = "converter-gson", version.ref = "retrofit" }
okhttp                      = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging              = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }

# ── Room ──────────────────────────────────────────────────────────────────────
room-runtime                = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx                    = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler               = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# ── Stockage sécurisé ─────────────────────────────────────────────────────────
# DataStore standard + security-crypto pour chiffrement avec MasterKey (Keystore AES-256-GCM)
datastore-preferences       = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
security-crypto             = { group = "androidx.security", name = "security-crypto-ktx", version.ref = "security-crypto" }

# ── Biométrie ─────────────────────────────────────────────────────────────────
biometric                   = { group = "androidx.biometric", name = "biometric-ktx", version.ref = "biometric" }

# ── Glance (widgets) ──────────────────────────────────────────────────────────
glance-appwidget            = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
glance-material3            = { group = "androidx.glance", name = "glance-material3", version.ref = "glance" }

# ── WorkManager ───────────────────────────────────────────────────────────────
workmanager-ktx             = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workmanager" }

# ── Caméra + QR (scan pairing) ────────────────────────────────────────────────
camerax-core                = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camerax-camera2             = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle           = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-view                = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
mlkit-barcode               = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkit-barcode" }

# ── Sécurité ──────────────────────────────────────────────────────────────────
rootbeer                    = { group = "com.scottyab", name = "rootbeer-lib", version.ref = "rootbeer" }

# ── Tests ─────────────────────────────────────────────────────────────────────
junit                       = { group = "junit", name = "junit", version.ref = "junit" }
mockk                       = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine                     = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
coroutines-test             = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
compose-ui-test             = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest    = { group = "androidx.compose.ui", name = "ui-test-manifest" }

[plugins]
android-application         = { id = "com.android.application", version.ref = "agp" }
kotlin-android              = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose              = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt                        = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp                         = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room                        = { id = "androidx.room", version.ref = "room" }
```

---

## Notes importantes

### Compatibilité KSP / Kotlin
La version KSP (`ksp = "2.0.21-1.0.28"`) doit correspondre **exactement** à la version
Kotlin. Format : `{kotlin_version}-{ksp_patch}`. Vérifier à chaque upgrade Kotlin.

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
Toutes les dépendances Compose (ui, material3, ui-tooling…) doivent utiliser le BOM
sans préciser de version individuelle :
```kotlin
implementation(platform(libs.compose.bom))
implementation(libs.compose.ui)        // pas de version.ref ici
implementation(libs.compose.material3)
```
