# Tests locaux — App Android + Backend `make dev`

Guide pour tester l'application Android sur le backend local sans VPS ni Radxa.

---

## Ce qui est bypassé en debug

Deux gardes de sécurité sont désactivées automatiquement quand `BuildConfig.DEBUG = true`
(build debug uniquement — sans effet en release) :

| Garde | Comportement release | Comportement debug |
|-------|---------------------|-------------------|
| `VpnRequiredInterceptor` | Bloque si VPN inactif | Laisse passer toutes les requêtes |
| `CertificatePinnerProvider` | Vérifie les SHA-256 pins | Aucun pinning (HTTP accepté) |

> Ces bypasses ne touchent **pas** le code de production. `assembleRelease` compile avec
> les gardes actives.

---

## Prérequis

- Backend `trading-platform` en cours d'exécution (`make dev` ou `make dev-local-all`)
- Android Studio + émulateur API 28+, **ou** téléphone physique sur le même réseau Wi-Fi
- `google-services.json` dans `app/` pour que le build compile (FCM/Crashlytics)
  → Récupérer depuis Firebase Console du projet, ou désactiver le plugin Crashlytics
  temporairement dans `app/build.gradle.kts`

---

## Configuration `local.properties`

Copier `local.properties.example` vers `local.properties` et remplir :

### Émulateur Android (accès au host via `10.0.2.2`)

```properties
VPS_BASE_URL=http://10.0.2.2:8000

# Valeurs fictives — ignorées en debug (pas de pinning)
CERT_PIN_SHA256=sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
CERT_PIN_SHA256_BACKUP=sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=

# Valeurs fictives — VPN bypassé en debug
WG_VPS_ENDPOINT=localhost:51820
WG_VPS_PUBKEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
```

### Téléphone physique (sur le même réseau Wi-Fi que la machine de dev)

```properties
# Remplacer par l'IP LAN de ta machine (ex: ifconfig | grep "inet 192")
VPS_BASE_URL=http://192.168.1.XX:8000

CERT_PIN_SHA256=sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
CERT_PIN_SHA256_BACKUP=sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
WG_VPS_ENDPOINT=localhost:51820
WG_VPS_PUBKEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
```

> **Pourquoi port 8000 ?** L'API gateway tourne sur le port `8000` en local (Docker
> `- "8000:8000"`). Le port `443` est le port de production derrière WireGuard (Caddy TLS).

---

## Démarrer le backend

```bash
cd ~/Codes/trading-platform

# Infrastructure (PostgreSQL + Redis)
make dev

# Peupler la base avec des utilisateurs de test
make dev-seed-users

# Peupler avec des données de trading (positions, P&L, alertes)
make dev-seed-trading

# Démarrer tous les services Python localement
make dev-local-all
```

L'API gateway est ensuite disponible sur `http://localhost:8000`.

---

## Builder et lancer l'app

```bash
cd ~/Codes/app-android
./gradlew assembleDebug
# Installer sur l'émulateur connecté
adb install app/build/outputs/apk/debug/app-debug.apk
```

Ou lancer directement depuis Android Studio avec le bouton **Run**.

---

## Ce que tu peux tester

| Fonctionnalité | Testable sans infra | Notes |
|----------------|---------------------|-------|
| Login + TOTP | ✅ | Utiliser les credentials de `make dev-seed-users` |
| Dashboard P&L | ✅ | Données de `make dev-seed-trading` |
| Positions + détail | ✅ | |
| Alertes (liste) | ✅ | Alertes stockées Room depuis FCM |
| Alertes (réception FCM) | ⚠️ | Nécessite `google-services.json` réel + appareil physique |
| Widgets | ✅ | WorkManager bypass VPN → données Room |
| Biométrie | ✅ | Sur appareil physique, simulateur limité |
| Devices / Pairing | ❌ | Nécessite une Radxa réelle sur le LAN |
| VPN settings | ⚠️ | L'UI s'affiche mais le tunnel ne se connecte pas |

---

## Credentials de test

Après `make dev-seed-users`, les comptes disponibles sont définis dans
`trading-platform/scripts/seed/users.py` (ou équivalent). Vérifier les logs de la commande
pour les emails/mots de passe générés.

---

## Problèmes fréquents

### `CLEARTEXT communication not permitted`

Le `network_security_config.xml` autorise déjà le cleartext (`cleartextTrafficPermitted="true"`).
Si l'erreur persiste, vérifier que `VPS_BASE_URL` utilise bien `http://` et non `https://`.

### `Connection refused` depuis l'émulateur

- Vérifier que le backend tourne : `curl http://localhost:8000/health/ready`
- L'émulateur accède au host via `10.0.2.2`, pas `localhost` ni `127.0.0.1`

### `Connection refused` depuis un téléphone physique

- Vérifier que le téléphone et la machine sont sur le même réseau Wi-Fi
- Vérifier que le firewall local n'bloque pas le port 8000 :
  ```bash
  # Linux
  sudo ufw allow 8000
  ```

### Build échoue — `google-services.json` manquant

Ajouter le fichier `app/google-services.json` depuis Firebase Console.
Alternative temporaire : commenter le plugin dans `app/build.gradle.kts` :
```kotlin
// alias(libs.plugins.google.services)
// alias(libs.plugins.firebase.crashlytics)
```
et retirer les dépendances `firebase-*` des `dependencies {}`.

### Login échoue — `401` ou `CSRF error`

L'`api-gateway` doit être démarré (pas seulement l'infra). Vérifier :
```bash
curl http://localhost:8000/csrf-token
# Doit retourner un token JSON
```

---

## Tests instrumentés en local — Gradle Managed Devices

Le job CI `instrumented` (`.github/workflows/android.yml`) lance les tests `androidTest` sur
deux émulateurs Gradle Managed Devices (GMD) déclarés dans `app/build.gradle.kts` —
`android.testOptions.managedDevices.localDevices` :

| Nom | Device | API | Image | Rôle |
|-----|--------|-----|-------|------|
| `api30` | Pixel 5 | 30 | `aosp-atd` | épingle #9 (`Instant.parse` pré-JDK12) |
| `api34` | Pixel 6 | 34 | `aosp-atd` | FGS/VPN (`foregroundServiceType="specialUse"`), chemin `BiometricPrompt` actuel |

Les images `aosp-atd` (Automated Test Device) n'ont pas Play Store/GMS — démarrage plus rapide,
comportement plus déterministe pour du CI/local headless, mais **pas de biométrie enrôlée** :
`BiometricLockOverlayInstrumentedTest` teste donc le chemin fail-closed (`onFailure`), pas un
déverrouillage réussi (qui demanderait un vrai capteur/émulateur avec empreinte simulée).

### Lancer un device localement

```bash
# Un seul device à la fois (voir contrainte RAM ci-dessous)
./gradlew api30DebugAndroidTest --stacktrace

# L'émulateur GMD démarre, tourne les tests androidTest, puis s'arrête automatiquement.
# Rapports : app/build/reports/androidTests/managedDevice/debug/api30/
```

La première exécution télécharge l'image système `aosp-atd` correspondante (plusieurs centaines
de Mo) — attendre la fin du téléchargement avant de juger d'un blocage.

### ⚠ Contrainte RAM — ne pas cumuler avec un autre build Gradle

Un émulateur GMD consomme environ **2 à 3 Go de RAM** en plus du process Gradle lui-même
(cohérent avec la contrainte `-Xmx3g` déjà appliquée au job CI, voir `docs/gradle-setup.md`
§CI/CD). Sur une machine de dev avec peu de RAM libre :

- **Ne pas lancer `api30DebugAndroidTest`/`api34DebugAndroidTest` en parallèle d'un autre build
  Gradle** (`assembleDebug`, `testDebugUnitTest`, Android Studio en cours d'indexation/build,
  etc.) — risque d'OOM ou de swap qui rend la machine inutilisable.
- **Ne pas lancer les deux devices dans la même invocation** en local
  (`api30DebugAndroidTest api34DebugAndroidTest` comme fait le job CI) sauf machine avec
  beaucoup de RAM libre — préférer un device à la fois pour du debug local.
- Fermer l'émulateur Android Studio habituel (s'il tourne) avant de lancer un test GMD — KVM ne
  partage pas bien entre deux émulateurs concurrents sur une machine limitée.
- `--no-daemon` (comme en CI) réduit l'empreinte mémoire résiduelle entre deux runs, au prix
  d'un démarrage Gradle plus lent à chaque invocation.

### Nettoyer un device GMD

```bash
./gradlew --stop
# Liste les AVD gérés par Gradle (nommés d'après les devices déclarés, ex. dev30_*/dev34_*)
ls ~/.android/avd/
# Supprimer celui qui pose problème (image corrompue, libérer l'espace disque) :
rm -rf ~/.android/avd/<nom_affiché_par_gradle>.avd ~/.android/avd/<nom_affiché_par_gradle>.ini
```
