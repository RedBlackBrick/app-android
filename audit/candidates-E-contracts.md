# Candidates — contract drift reviewer (Sonnet, evidence both sides) — status: candidate (strong evidence)

HIGH
- E-contract-1 | MarketDataApi.kt:14-15 | GET v1/market-data/symbols declared List<String>; backend router.py:233 returns SymbolListResponse object {symbols:[SymbolListItem],total,...} → Moshi "Expected BEGIN_ARRAY" → symbol picker always fails. conf high
- E-contract-2 | MarketDataApi.kt:17-22 | history: app sends interval/limit, backend requires start/end (Query(...)) and names it timeframe; response is object {symbol,data,...} not array → 422 every time → sparklines never render. conf high
- E-contract-3 | PublicWsClient.kt:357 | volume parsed with toLongOrNull() but backend market_data_bridge.py:329 sends str(float) "1234.0" → always 0. conf high
- E-contract-4 | PositionDto.kt:11-12 | quantity/average_price non-null BigDecimal; backend PositionResponse quantity/average_price: Decimal|None → JsonDataException on null → whole positions/dashboard fetch fails. conf medium

Consistent: auth (login/refresh/logout/me/2fa/ws-token), portfolios (detail/positions shape/performance/pnl/transactions), quote, orders, risk CB, strategies, edge devices, commands, vpn-peers, broker-connections, fcm-token, WS private+public event names, CSRF exempt paths + root /csrf-token alias (main.py:1523), AUTH_1002/1003, 426, LAN pairing (HTTPS 8099, sealed payload, status vocab), mobile-provisioning register.
→ Resolves A-corr-3 (CSRF path): REJECTED — backend exposes root /csrf-token alias for Android. Note: this reviewer states /v1/auth/2fa/verify matches; whether AuthInterceptor blocks it (A-corr-1) is a separate app-side question under verification.
