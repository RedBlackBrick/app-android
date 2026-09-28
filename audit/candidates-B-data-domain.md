# Candidates — lead B (data-domain) — status: candidate

CRITICAL (as reported; orchestrator will re-rank after verification)
- B-room-1 | DatabaseModule.kt:38, AppDatabase.kt:24 | MIGRATION_1_2 never defined; v1→current has no path; release has no destructive fallback → crash loop. conf high
- B-room-2 | AppDatabase.kt:74-83 | MIGRATION_3_4 only adds indices; pnl_snapshots columns restructured 3→4 (7 old→10 new) not migrated → schema validation fails. conf high
- B-room-3 | AppDatabase.kt:108-117 | MIGRATION_4_5 only creates watchlist; NOT NULL relaxations on positions/devices/quotes not migrated → validation fails. conf high
- B-pairing-1 | MobileProvisioningRepositoryImpl.kt:68 | suspend register() does blocking OkHttp execute() without Dispatchers.IO; called from viewModelScope → NetworkOnMainThreadException. conf high
- B-dto-1 | Mappers.kt:74-98 | PerformanceResponseDto→PerformanceMetrics lacks /100 normalization that PnlResponseDto has; UI multiplies by 100 → "+1050.00%". conf high (2 reviewers independently)
- B-auth-conc-1 (systemic) | all *RepositoryImpl + ConfirmPairingUseCase | runCatching swallows CancellationException → cancellation misreported as failure. conf high — ORCHESTRATOR: likely medium, not critical; CLAUDE.md template itself lacks the guard
- B-dto-2 | BigDecimalAdapter.kt:9 | BigDecimal(value) unguarded; one malformed field fails whole list parse. conf high — ORCHESTRATOR: arguably by-design fail-fast; likely medium

HIGH
- B-dto-3 | Mappers.kt:71,131,142,155,193 + InstantAdapter | Instant.parse ad hoc in mappers, InstantAdapter never used by any DTO; one bad timestamp fails whole list. conf high
- B-ws-b-1 | PublicWsClient.kt:79,148-157 | activeSymbols has no per-consumer ref-count; Dashboard unsubscribing kills MarketData stream for same symbol (singleton). conf high
- B-ws-b-2 | PublicWsClient.kt:267-287, PublicWsRepositoryImpl.kt:37-46 | connection failures never propagate through Flow<Quote>; MarketDataViewModel's REST fallback catch is dead code. conf high
- B-pm-2 | MarketDataRepositoryImpl.kt:58-75 | in-flight dedup via shared CompletableDeferred + runCatching → one caller's cancellation delivered as failure to others. conf high
- B-pm-3 | PortfolioRepositoryImpl.kt:34-54 | getPosition cache-miss fetches only OPEN → closed position detail always "not found" after cache purge. conf high
- B-misc-2 | PortfolioCircuitBreakerStatus.kt:30-33 | fromWire unknown → CLOSED (trading active) instead of unknown/fail-closed. conf high
- B-pairing-conc-2 | ConfirmPairingUseCase.kt:35-40 | catch(Exception) after TimeoutCancellationException swallows plain CancellationException. conf high
- B-ws-a-1 | LogoutUseCase.kt:16-22 | PrivateWsClient.disconnect() has zero call sites; WS stays connected/reconnecting after logout. conf high
- B-ws-a-2 | TradingApplication.kt:75-97 | PrivateWsClient.connect() only at cold start if token present; fresh login never connects private WS → no live updates until restart. conf high
- B-misc-1 | 8 repositories | same runCatching pattern (merge with B-auth-conc-1)

Cross-module: PortfolioRepositoryImpl.getPnl() shares 100x bug but has no live callers; no repository unit tests at all; no MigrationTestHelper androidTest; migrations bugs invisible in debug (fallbackToDestructiveMigration).
