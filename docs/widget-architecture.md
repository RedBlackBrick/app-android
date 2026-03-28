# Architecture des Widgets Glance

Ce document decrit l'architecture des widgets Android de l'application de trading. Tous les
widgets utilisent le framework Jetpack Glance et lisent leurs donnees depuis le cache Room
local. Aucun widget ne fait d'appel reseau directement.

---

## Inventaire des widgets

L'application declare cinq widgets, chacun associe a un `GlanceAppWidgetReceiver` et un fichier
XML `appwidget-provider` dans `res/xml/`.

### PnlWidget

- **Classe** : `PnlWidget` / `PnlWidgetReceiver`
- **Taille minimale** : 2x1 cellules (110x40 dp)
- **Configurable** : oui -- `PnlWidgetConfigureActivity` permet de choisir la periode (jour,
  semaine, mois). La periode est stockee dans `SharedPreferences` sous la cle
  `period_$appWidgetId` (fichier `pnl_widget_prefs`).
- **Donnees affichees** :
  - P&L total de la periode configuree (`totalReturn`, `totalReturnPct`)
  - Couleur verte si positif, rouge si negatif (via `WidgetColors`)
  - Timestamp `syncedAt` de la derniere synchronisation
- **Source Room** : table `pnl_snapshots` via `PnlDao.getLatestByPeriod(period)`
- **Action tap** : ouvre `MainActivity` (DashboardScreen)

> **Note** : `PnlWidgetConfigureActivity` existe dans le code mais n'est pas encore referencee
> dans `pnl_widget_info.xml` (pas d'attribut `android:configure`). L'activite est fonctionnelle
> et affiche un apercu visuel avec selection de periode (Jour / Semaine / Mois).

### PositionsWidget

- **Classe** : `PositionsWidget` / `PositionsWidgetReceiver`
- **Taille minimale** : 2x2 cellules (110x80 dp)
- **Configurable** : non
- **Donnees affichees** :
  - Top 5 positions ouvertes : symbole + P&L non realise (`unrealizedPnl`)
  - Couleur P&L verte/rouge selon le signe
  - Timestamp `syncedAt` (max parmi les positions affichees)
  - Message "Aucune position ouverte" si la liste est vide
- **Source Room** : table `positions` via `PositionDao.getAll()`, limite aux 5 premiers
- **Action tap** : ouvre `MainActivity` (PositionsScreen)

### AlertsWidget

- **Classe** : `AlertsWidget` / `AlertsWidgetReceiver`
- **Taille minimale** : 2x1 cellules (110x40 dp)
- **Configurable** : non
- **Donnees affichees** :
  - Compteur d'alertes non lues
  - Titre de la derniere alerte (gras si non lue, normal sinon)
  - Timestamp relatif de la derniere alerte (format : "maintenant", "il y a Xmin", "il y a Xh",
    ou "dd/MM HH:mm" au-dela de 24h)
  - Message "Aucune alerte" si la table est vide
- **Source Room** : table `alerts` via `AlertDao.getAll()` (triee par `received_at DESC`)
- **Action tap** : ouvre `MainActivity` (AlertListScreen)

Les alertes proviennent exclusivement de FCM et sont persistees localement dans Room. Le
`WidgetUpdateWorker` ne synchronise pas les alertes depuis le reseau -- il effectue uniquement
la purge des alertes expirees (30 jours ou 500 entrees max).

### SystemStatusWidget

- **Classe** : `SystemStatusWidget` / `SystemStatusWidgetReceiver`
- **Taille minimale** : 2x1 cellules (110x40 dp)
- **Configurable** : non
- **Reserve aux administrateurs** : oui (double mecanisme, voir section dediee ci-dessous)
- **Donnees affichees** :
  - Nombre de devices online vs offline (via `DeviceStatus.fromApiString()`)
  - Nom du device avec le heartbeat le plus recent
  - Timestamp `syncedAt` (max parmi les devices)
  - Message "Reserve aux administrateurs" si `is_admin == false`
  - Message "Aucun device enregistre" si la liste est vide
- **Source Room** : table `devices` via `DeviceDao.getAll()`
- **Action tap** : ouvre `MainActivity` (DevicesScreen)

### QuoteWidget

- **Classe** : `QuoteWidget` / `QuoteWidgetReceiver`
- **Taille minimale** : 1x1 cellule (55x40 dp)
- **Configurable** : oui -- `QuoteWidgetConfigureActivity` (declaree dans `quote_widget_info.xml`
  via `android:configure`)
- **Donnees affichees** :
  - Symbole boursier configure (defaut : `AAPL`)
  - Prix du cours (`price`)
  - Variation en pourcentage (`changePercent`), colore vert/rouge
  - Timestamp `syncedAt`
- **Source Room** : table `quotes` via `QuoteDao.getBySymbol(symbol)`
- **Action tap** : ouvre `MainActivity`

Chaque instance de QuoteWidget peut afficher un symbole different. Le symbole est stocke dans
`SharedPreferences` (fichier `quote_widget_prefs`) sous la cle `ticker_$appWidgetId`.

---

## Framework Glance

### Pattern GlanceAppWidget + GlanceAppWidgetReceiver

Chaque widget suit la meme structure :

1. Une classe `GlanceAppWidget` qui implemente `provideGlance()` -- point d'entree ou les
   donnees sont lues depuis Room et le contenu Glance est construit.
2. Une classe `GlanceAppWidgetReceiver` minimale qui instancie le widget :

```kotlin
class PnlWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PnlWidget()
}
```

3. Un fichier XML `appwidget-provider` dans `res/xml/` qui declare la taille minimale, le
   layout initial (`@layout/widget_loading`), et les options de redimensionnement.

Les receivers sont declares dans `AndroidManifest.xml` avec `android:exported="false"` et
l'intent filter `APPWIDGET_UPDATE`.

### Rendu Glance

Les widgets utilisent `GlanceTheme` pour les couleurs de surface et de texte. Les couleurs
specifiques au trading (P&L positif/negatif/neutre) sont definies dans `WidgetColors` :

```kotlin
internal object WidgetColors {
    val PnlPositive = Color(0xFF34D399)  // emerald-400
    val PnlNegative = Color(0xFFFB7185)  // rose-400
    val PnlNeutral  = Color(0xFF94A3B8)  // slate-400
}
```

Ces couleurs sont identiques a celles de `ExtendedColors` dans le theme principal mais
declarees en constantes car les composables Glance n'ont pas acces a `MaterialTheme` ni a
`LocalExtendedColors`.

### Formatage des timestamps

La fonction utilitaire `formatWidgetSyncTime()` (dans `WidgetTheme.kt`) formate les timestamps
`syncedAt` en labels lisibles :

- `< 1 min` : "maintenant"
- `< 60 min` : "il y a Xmin"
- `>= 60 min` : "HH:mm"

`AlertsWidget` utilise sa propre fonction `formatAlertTime()` avec une granularite differente
(inclut "il y a Xh" et le format "dd/MM HH:mm" au-dela de 24h).

---

## Injection de dependances (Hilt)

### EntryPointAccessors pour les GlanceAppWidget

Les `GlanceAppWidget` ne supportent pas `@AndroidEntryPoint`. L'injection se fait via
`EntryPointAccessors.fromApplication()` et l'interface `WidgetEntryPoint` declaree dans
`di/WidgetModule.kt` :

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    // UseCases
    fun getPositionsUseCase(): GetPositionsUseCase
    fun getPnlUseCase(): GetPnlUseCase
    fun getPortfolioNavUseCase(): GetPortfolioNavUseCase
    fun getAlertsUseCase(): GetAlertsUseCase
    fun getDevicesUseCase(): GetDevicesUseCase
    fun getQuoteUseCase(): GetQuoteUseCase

    // DAOs (lecture cache Room)
    fun positionDao(): PositionDao
    fun pnlDao(): PnlDao
    fun alertDao(): AlertDao
    fun deviceDao(): DeviceDao
    fun quoteDao(): QuoteDao

    // DataStore
    fun encryptedDataStore(): EncryptedDataStore
}
```

Usage dans chaque widget :

```kotlin
override suspend fun provideGlance(context: Context, id: GlanceId) {
    val ep = EntryPointAccessors
        .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
    val pnlDao = ep.pnlDao()
    // ...
}
```

Les widgets lisent le cache Room directement via les DAOs. Les UseCases exposes dans
`WidgetEntryPoint` sont destines au `WidgetUpdateWorker` (qui fait les appels reseau).

### @HiltWorker pour WidgetUpdateWorker

`WidgetUpdateWorker` utilise le pattern `@HiltWorker` + `@AssistedInject` qui est supporte
nativement par WorkManager :

```kotlin
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val vpnManager: WireGuardManager,
    private val dataStore: EncryptedDataStore,
    private val getPositionsUseCase: GetPositionsUseCase,
    private val getPnlUseCase: GetPnlUseCase,
    private val getQuoteUseCase: GetQuoteUseCase,
    private val alertDao: AlertDao,
    private val quoteDao: QuoteDao,
) : CoroutineWorker(context, workerParams) { ... }
```

---

## WidgetUpdateWorker

### Planification

Le Worker est planifie dans `TradingApplication.onCreate()` via `WorkManager` avec une
periodicite de **15 minutes** (minimum impose par l'OS) et la contrainte
`NetworkType.CONNECTED` :

```kotlin
private fun scheduleWidgetUpdateWorker() {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
        15, TimeUnit.MINUTES
    )
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        "widget_update",
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest,
    )
}
```

Le nom unique `"widget_update"` avec `KEEP` garantit qu'une seule instance periodique existe.

### Verification VPN

Le Worker verifie `vpnManager.state.value is VpnState.Connected` en entree de `doWork()`.
Si le VPN est absent :

- Retourne `Result.success()` (pas de retry -- cas previsible)
- Le cache Room existant reste affiche avec son timestamp date

### Verification portfolioId

Si `portfolioId` est `null` dans `EncryptedDataStore` (corruption possible apres invalidation
Keystore au reboot), le Worker retourne `Result.success()` sans effacer Room. Le cache existant
est preserve.

### Blocs de synchronisation independants

Chaque section est dans son propre `try/catch`. Un echec d'un bloc ne bloque pas les autres :

1. **Sync positions** -- `getPositionsUseCase(portfolioId)` puis upsert+purge atomique via le Repository
2. **Sync PnL** -- `getPnlUseCase(portfolioId, PnlPeriod.DAY)` puis upsert+purge atomique via le Repository
3. **Sync quotes** -- boucle sur tous les symboles en cache (`quoteDao.getAllSymbols()`), ou le
   symbole par defaut `AAPL` si la table est vide. Chaque symbole est synchronise independamment.
4. **Purge alertes** -- suppression des alertes de plus de 30 jours et au-dela des 500
   dernieres (`alertDao.purgeExpired(cutoff)`). Les alertes ne sont pas synchronisees depuis le
   reseau -- elles proviennent de FCM uniquement.
5. **Rafraichissement widgets** -- appel `updateAll()` sur les cinq widgets Glance pour qu'ils
   relisent Room dans leur `provideGlance()`.

### Strategie de retry

| Exception                  | Comportement                                           |
|----------------------------|--------------------------------------------------------|
| `VpnNotConnectedException` | Ignore (VPN coupe en cours de sync) -- pas de retry    |
| `IOException`              | Positionne `anyRetryNeeded = true` -- `Result.retry()` |
| `SQLException`             | Logge l'erreur -- pas de retry (erreur Room locale)    |
| Autres exceptions          | Loggees -- pas de retry                                |

`Result.retry()` est retourne si au moins un bloc a echoue avec une `IOException`.
WorkManager applique un `BackoffPolicy.EXPONENTIAL` par defaut.

### Timestamp de derniere tentative

Le Worker enregistre le timestamp de chaque tentative de `doWork()` (reussie ou non) dans
`SharedPreferences` plain (fichier `widget_sync_prefs`, cle `widget_last_sync_attempt`). Ce
timestamp non sensible est lu par les widgets pour afficher un label de secours ("Tentative
il y a Xmin") quand aucune donnee Room n'est disponible.

---

## Sources de donnees Room

Chaque widget lit une table Room specifique. Le `WidgetUpdateWorker` est responsable de la mise
a jour de ces tables (sauf `alerts` qui vient de FCM).

| Widget             | Table Room        | Champs utilises par le widget                               | TTL     |
|--------------------|-------------------|--------------------------------------------------------------|---------|
| PnlWidget          | `pnl_snapshots`   | `period`, `totalReturn`, `totalReturnPct`, `syncedAt`        | 5 min   |
| PositionsWidget    | `positions`       | `symbol`, `unrealizedPnl`, `syncedAt`                        | 5 min   |
| AlertsWidget       | `alerts`          | `title`, `read`, `receivedAt`                                | 30j/500 |
| SystemStatusWidget | `devices`         | `status`, `name`, `id`, `lastHeartbeat`, `syncedAt`          | 1 min   |
| QuoteWidget        | `quotes`          | `symbol`, `price`, `changePercent`, `syncedAt`               | 10 min  |

Toutes les entites Room portent un champ `syncedAt: Long` (epoch millis). Les widgets affichent
ce timestamp systematiquement -- c'est une contrainte pour une application de trading ou un
cours sans indication d'age serait trompeur.

---

## Visibilite des widgets admin

Le `SystemStatusWidget` est reserve aux comptes admin (`is_admin == true`). Deux mecanismes
complementaires assurent cette restriction :

### 1. PackageManager -- masquage dans le picker de widgets

`AdminWidgetVisibilityManagerImpl` utilise `PackageManager.setComponentEnabledSetting()` pour
activer ou desactiver le `SystemStatusWidgetReceiver` dans le launcher :

```kotlin
@Singleton
class AdminWidgetVisibilityManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AdminWidgetVisibilityManager {

    override suspend fun applyVisibility(isAdmin: Boolean) {
        val state = if (isAdmin)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, SystemStatusWidgetReceiver::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }
}
```

L'interface `AdminWidgetVisibilityManager` est definie dans la couche domain
(`domain/repository/AdminWidgetVisibilityManager.kt`) pour respecter l'inversion de
dependance. Le binding est declare dans `RepositoryModule`.

Cette methode est appelee apres le login (via `ApplyAdminWidgetVisibilityUseCase`) pour
synchroniser l'etat du composant avec le flag `is_admin` de l'utilisateur.

### 2. Verification is_admin dans provideGlance -- defense en profondeur

Dans `SystemStatusWidget.provideGlance()`, le flag `is_admin` est relu depuis
`EncryptedDataStore` avant d'acceder aux donnees devices. Si `is_admin == false`, le widget
affiche "Reserve aux administrateurs" et ne charge aucune donnee device.

---

## Configuration des widgets

### QuoteWidgetConfigureActivity

Declaree dans `quote_widget_info.xml` via `android:configure` et dans `AndroidManifest.xml`
avec l'intent filter `APPWIDGET_CONFIGURE`.

- Interface Jetpack Compose avec `TradingPlatformTheme`
- Champ de saisie du symbole boursier (capitalisation automatique, suppression des espaces)
- Apercu visuel du widget avec le symbole saisi
- Le symbole est persiste dans `SharedPreferences` (fichier `quote_widget_prefs`) sous la cle
  `ticker_$appWidgetId`
- Apres confirmation, le widget est mis a jour immediatement via `QuoteWidget().update()`
- Si la mise a jour immediate echoue, le `WidgetUpdateWorker` le fera au prochain cycle

Le `SharedPreferences` plain est utilise intentionnellement ici : les symboles boursiers sont
des identifiants publics non sensibles. Les donnees sensibles (tokens, IDs portfolio) sont
stockees dans `EncryptedDataStore`.

### PnlWidgetConfigureActivity

L'activite de configuration du PnlWidget existe dans le code (`PnlWidgetConfigureActivity.kt`)
avec une interface complete :

- Selection de la periode parmi trois options : Jour (`day`), Semaine (`week`), Mois (`month`)
- Apercu visuel du widget avec la periode selectionnee
- Persistance dans `SharedPreferences` (fichier `pnl_widget_prefs`, cle `period_$appWidgetId`)

> **Note** : cette activite n'est pas encore referencee dans `pnl_widget_info.xml` (pas
> d'attribut `android:configure`) et n'est pas declaree dans `AndroidManifest.xml`. La periode
> par defaut utilisee est `"day"`.

---

## Verrou biometrique et widgets

Les widgets ne sont pas proteges par le verrou biometrique. C'est une decision intentionnelle :

- Les widgets lisent des donnees Room en cache via le `WidgetUpdateWorker`, pas via l'ecran
  principal
- L'ecran d'accueil Android est deja protege par le verrou OS (PIN, empreinte, face unlock)
- Les widgets affichent des montants absolus (P&L en euros, positions en valeur)
- Le `WireGuardVpnService` reste actif independamment du verrou (service foreground)

---

## Comportement hors ligne

Quand le VPN est inactif ou que le reseau est indisponible :

1. Le `WidgetUpdateWorker` retourne `Result.success()` sans modifier Room
2. Les widgets affichent le cache Room existant avec le timestamp `syncedAt`
3. Si aucune donnee n'est en cache, les widgets affichent un tiret ("--") avec le timestamp
   de la derniere tentative de sync du Worker
4. Si `portfolioId` est `null` dans `EncryptedDataStore`, les widgets PnlWidget et
   PositionsWidget affichent "Session expiree -- ouvrez l'app"

Le `AlertsWidget` fonctionne entierement offline puisque ses donnees proviennent de FCM
persiste dans Room, sans synchronisation reseau.

---

## Fichiers sources

```
widget/
  PnlWidget.kt                         GlanceAppWidget P&L
  PnlWidgetReceiver.kt                 GlanceAppWidgetReceiver
  PnlWidgetConfigureActivity.kt        Configuration periode (non enregistree dans le manifest)
  PositionsWidget.kt                    GlanceAppWidget positions
  PositionsWidgetReceiver.kt            GlanceAppWidgetReceiver
  AlertsWidget.kt                       GlanceAppWidget alertes
  AlertsWidgetReceiver.kt               GlanceAppWidgetReceiver
  SystemStatusWidget.kt                 GlanceAppWidget etat systeme (admin)
  SystemStatusWidgetReceiver.kt         GlanceAppWidgetReceiver
  QuoteWidget.kt                        GlanceAppWidget cours
  QuoteWidgetReceiver.kt                GlanceAppWidgetReceiver
  QuoteWidgetConfigureActivity.kt       Configuration symbole boursier
  WidgetUpdateWorker.kt                 Worker periodique (sync Room + refresh widgets)
  AdminWidgetVisibilityManagerImpl.kt   Masquage PackageManager des widgets admin
  WidgetTheme.kt                        Couleurs et formatage timestamp partages

di/
  WidgetModule.kt                       WidgetEntryPoint (@EntryPoint pour Glance)

res/xml/
  pnl_widget_info.xml                   appwidget-provider (2x1, sans configure)
  positions_widget_info.xml             appwidget-provider (2x2)
  alerts_widget_info.xml                appwidget-provider (2x1)
  system_status_widget_info.xml         appwidget-provider (2x1)
  quote_widget_info.xml                 appwidget-provider (1x1, avec configure)
```
