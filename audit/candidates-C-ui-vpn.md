# Candidates — lead C (app-ui-vpn) — status: candidate

CRITICAL (as reported)
- C-auth-sec-1 | MainActivity.kt:94-127, AppNavGraph.kt:248,807 | MainActivity.onBiometricUnlocked() never called; overlay unlock only calls biometricLockManager.unlock(); MainActivity.isBiometricLocked stays true → inactivity lock never re-arms. conf high

HIGH
- C-admin-1 (merged ×4) | MyDevicesScreen.kt:59-92 FAB, AppNavGraph.kt:655-751 pairing graph, PairingViewModel, DevicesViewModel | no is_admin check anywhere on pairing flow; MyDevices (all users) links to pairing. conf high
- C-vpn-conc-1 | WireGuardManager.kt:89-150 | connect()/disconnect() mutate backend/currentTunnel from separate coroutines, no Mutex → disconnect during connect leaves tunnel UP. conf medium
- C-dash-corr-1 | DashboardScreen.kt:72, DashboardViewModel.kt:310-314,453 | isRefreshing derived from Loading; every WS portfolio_update → fetchNav/fetchPnl set Loading → spinner flicker. conf high
- C-pos-corr-1 | PositionsViewModel.kt:66-97 | matchesPosition falls back to symbol match when positionId doesn't match → update for lot 99 overwrites lot 42 same symbol. conf high
- C-tx-conc-1 | TransactionHistoryViewModel.kt:34-76 | loadMore/refresh no re-entrancy guard → duplicated page + skipped offset. conf high
- C-alerts-corr-1 | AlertsViewModel.kt:50-71 | .catch{} sets Error, flow completes → never resubscribes; list frozen until VM recreated. conf high
- C-dev-corr-1 | DevicesViewModel.kt:147-163 + DeviceRepositoryImpl.getDeviceStatus | syncedAt = now() even from cache; repo returns cached row with no TTL → refresh no-op shown as fresh. conf high
- C-pair-sec-1 | PairingViewModel.kt:75-297 | no VpnState check before LAN sendPin/confirm (CLAUDE §8). conf medium
- C-a11y-1 | PnlText/AnimatedPnlText/MoneyText/StatusBadge/VpnStatusBanner/ErrorBanner/ConnectionStatusIndicator | semantics{contentDescription} without mergeDescendants → raw text still separately focusable. conf medium
- C-auth-sec-2 | MainActivity.kt:79,101-114 | Activity recreation (rotation) resets lastInteractionAt → inactivity lock postponable. conf high

MEDIUM
- C-vpn-err-1 | WireGuardVpnService.kt | no onRevoke() override → stale Connected/notification after OS revocation.
- C-vpn-corr-2 | WireGuardManager.kt:157-168 | Connected derived from Tunnel.State.UP, not handshake.
- C-vpn-sec-1 | VpnSettingsViewModel.kt:42-53, VpnSettingsScreen.kt:156 | any system VPN → "Tunnel WireGuard actif" message.
- C-dash-conc-1/2 | DashboardViewModel.kt:329-365, MarketDataViewModel.kt:183-209 | WS failure → REST polling forever, never resubscribes.
- C-dash-perf-1 | MarketDataViewModel.kt:190 | no Flow.sample(250ms) (CLAUDE §2) — NOTE: verifier B-ws-b-2 evidence shows sample(250) in PublicWsRepositoryImpl.kt:45 → likely REJECTED (throttle is in repository)
- C-dash-corr-4 | DashboardViewModel.kt:220-230 | NAV/PnL no periodic refetch (only init/manual/WS).
- C-pos-conc-2 | PositionsViewModel.kt:54-57,99-124 | selectFilter/refresh don't cancel in-flight load → out-of-order overwrite.
- C-orders-corr-2 | OrdersViewModel.kt:50-58 | init fetches with possibly empty portfolioId; refresh guards it; corruption → should force logout.
- C-orders-corr-3 | OrdersViewModel.kt:86-97 | history capped 50, count discarded, no pagination.
- C-settings-cov-1 | SettingsViewModel/ProfileViewModel/MyDevicesViewModel untested. (test-gap)
- C-dev-sec-1 | SendDeviceCommandUseCase.kt:12-17 | free-form commandType string, no allow-list.
- C-pair-corr-1 | PairingViewModel.kt:103-146 | QR misread after one scan → Error discards scanned QR; reset() → Idle.
- C-pair-corr-2 | PairingViewModel.kt:162-174 | BothScanned branch doesn't update _deviceInfo → stale device card.
- C-comp-corr-1 | CacheTimestamp.kt:35-52 | 1/5-min thresholds vs documented 10 min.
- C-comp-corr-2 | QrScannerView.kt:133-213 | onDispose lacks cameraProvider.unbindAll().
- C-sec-1 | CertificatePinner.kt:19-25 | DEV_MODE → pinning null (debug only; CLAUDE §1 forbids even for tests).
- C-comp-perf-1 | HapticFeedback.kt:52-55 | rememberHapticFeedback doesn't remember.
- C-auth-sec-3 | TradingApplication.kt:67-72, BiometricLockManager.kt:37-47 | restorePersistedState fire-and-forget; nav gate may render before overlay.
- C-auth-corr-1 | SetupViewModel.kt:61,110 | VM writes EncryptedDataStore directly (layering violation), untested.
- C-misc | SymbolPickerSheet.kt:154-200 casing desync; ActivityFeedCard.kt:179 relative time never refreshes.

LOW: deviceWgIp never displayed (CLAUDE §8); deep-link clearDeepLink only for "alerts"; CrashlyticsTree no redaction net; no BackHandler on lock overlay; snackbar keyed on message; MarketDataScreen.kt:449-452 hardcoded Color(0xFF...) (§5); PositionsViewModel syncedAt=now; collectPositionWsUpdates no catch; OrdersViewModel untested; SecuritySettingsViewModel stale doc; NumberFormat alloc per recomposition; NetworkUtils regex instead of Patterns.IP_ADDRESS.

Cross-module: docs say LAN pairing is HTTP but code uses HTTPS + LanTrustManager (doc drift). One "critical" VPN claim rejected by lead itself.
