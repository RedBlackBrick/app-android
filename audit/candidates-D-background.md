# Candidates — lead D (background: widget/, fcm/, TradingApplication) — status: candidate

HIGH
- D-wuw-correctness-2 | WidgetUpdateWorker.kt:123-130,197 | Result.retry() only when ALL 3 sections fail (doc says ≥1) → PnL stale indefinitely on partial 503. conf high
- D-wuw-errorhandling-1 | WidgetUpdateWorker.kt:137-172 | per-section catch only IOException/VpnNotConnected/SQLException; GeneralSecurityException etc. propagates via awaitAll → whole doWork crashes, no purge/no refresh. conf high
- D-fcmworker-errorhandling-1 | FcmTokenRegistrationWorker.kt:57-68 + NotificationRepositoryImpl.kt:15-27 | non-2xx → IllegalStateException → Result.failure(); 503 treated as permanent. conf high
- D-fcmworker-errorhandling-2 | FcmTokenRegistrationWorker.kt:50-69 + TradingApplication.kt:91-106 | failure never clears PENDING_FCM_*; app re-enqueues with REPLACE every cold start → unbounded retry loop resetting backoff. conf high
- D-widgets-dataintegrity-1 | WidgetTheme.kt:32-39 | formatWidgetSyncTime shows bare HH:mm for data >60min (days-old shown as today's time). conf high

MEDIUM
- D-widgetconfig-correctness-1 | PnlWidgetConfigureActivity.kt + pnl_widget_info.xml | activity not in manifest, no android:configure → dead code, period never configurable. conf high
- D-widgetconfig-correctness-2 | ProfileViewModel.kt:57 / ApplyAdminWidgetVisibilityUseCase | admin demotion via /auth/me never re-applies widget visibility. conf medium
- D-widgets-correctness-1 | SystemStatusWidget.kt:58 | readBoolean null (corrupt datastore) → "Réservé aux administrateurs" instead of "Session expirée". conf high
- D-widgets-correctness-2 | PositionsWidget.kt:56 + PositionDao | "Top 5" = first 5 alphabetically. conf high
- D-wuw-correctness-1 | WidgetUpdateWorker.kt:261-268 | quotes sync only refreshes symbols already in quoteDao, not watchlist/widget tickers. conf medium (open: does AddToWatchlist seed quoteDao?)
- D-fcmworker-correctness-1 | FcmTokenRegistrationWorker.kt:50-56 | success path removes PENDING_* unconditionally; race with onNewToken writing T2 → T2 never sent. conf medium
- D-fcm-correctness-1 | TradingFirebaseMessagingService.kt:92-98 | Room insert in serviceScope.launch, onMessageReceived returns before commit → alert loss if process killed. conf medium
- D-wuw-performance-2 | WidgetUpdateWorker.kt:70-75,261-296 | syncQuotes no symbol cap / no overall timeout. conf medium

LOW
- D-tradingapp-correctness-1 | TradingApplication.kt:123-127 vs CLAUDE.md | doc says 5 min, code 15 min (OS floor) — doc wrong.
- D-fcm-correctness-2 | TradingFirebaseMessagingService.kt:165 | notification id = currentTimeMillis().toInt() collision.
- D-wuw-performance-1 | WidgetUpdateWorker syncQuotes | one upsertAndPurge transaction per symbol.
- D-tradingapp-correctness-2 | AndroidManifest.xml:21 | RECEIVE_BOOT_COMPLETED declared, no own receiver (WorkManager uses it) — hygiene.
- D-fcm-security-1 | TradingFirebaseMessagingService.kt:70-90 | FCM title/body stored without truncation.
- D-wuw-performance-3 | WidgetUpdateWorker.kt:131-188 | purgeExpiredAlerts serialized after network awaitAll.

Cross-module notes: refreshAllWidgets() calls SystemStatusWidget.updateAll unconditionally (relies on PackageManager state); admin gate vs session-expired gate implemented divergently across widgets.
