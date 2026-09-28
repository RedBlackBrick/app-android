package com.tradingplatform.app.contracts

import com.squareup.moshi.Json
import com.tradingplatform.app.data.api.ActiveOrdersResponseDto
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.api.BrokerConnectionApi
import com.tradingplatform.app.data.api.BrokerConnectionDto
import com.tradingplatform.app.data.api.DeviceApi
import com.tradingplatform.app.data.api.DeviceCommandRequestDto
import com.tradingplatform.app.data.api.DeviceListResponseDto
import com.tradingplatform.app.data.api.MarketDataApi
import com.tradingplatform.app.data.api.MyDevicesApi
import com.tradingplatform.app.data.api.NotificationApi
import com.tradingplatform.app.data.api.OrderDto
import com.tradingplatform.app.data.api.OrderHistoryResponseDto
import com.tradingplatform.app.data.api.OrdersApi
import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.api.PortfolioCircuitBreakerStatusDto
import com.tradingplatform.app.data.api.PortfolioStrategyLinkDto
import com.tradingplatform.app.data.api.RiskApi
import com.tradingplatform.app.data.api.StrategiesApi
import com.tradingplatform.app.data.model.BrokerSummaryDto
import com.tradingplatform.app.data.model.DeviceDto
import com.tradingplatform.app.data.model.FcmTokenRequestDto
import com.tradingplatform.app.data.model.FcmTokenResponseDto
import com.tradingplatform.app.data.model.LoginRequestDto
import com.tradingplatform.app.data.model.LoginResponseDto
import com.tradingplatform.app.data.model.MarketDataPointDto
import com.tradingplatform.app.data.model.MarketDataResponseDto
import com.tradingplatform.app.data.model.NavResponseDto
import com.tradingplatform.app.data.model.PerformanceResponseDto
import com.tradingplatform.app.data.model.PnlResponseDto
import com.tradingplatform.app.data.model.PortfolioDetailDto
import com.tradingplatform.app.data.model.PortfolioDto
import com.tradingplatform.app.data.model.PortfolioSummaryDto
import com.tradingplatform.app.data.model.PositionDto
import com.tradingplatform.app.data.model.QuoteDto
import com.tradingplatform.app.data.model.SymbolListItemDto
import com.tradingplatform.app.data.model.SymbolListResponseDto
import com.tradingplatform.app.data.model.TokenResponseDto
import com.tradingplatform.app.data.model.TotpVerifyRequestDto
import com.tradingplatform.app.data.model.TotpVerifyResponseDto
import com.tradingplatform.app.data.model.UserDto
import com.tradingplatform.app.data.model.VpnPeerDto
import com.tradingplatform.app.data.model.VpnPeerListResponseDto
import com.tradingplatform.app.data.model.WsTokenResponseDto
import org.json.JSONObject
import org.junit.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertTrue

/**
 * Contract-drift guard (audit/PLAN.md Phase 0 §3, design: audit/plan-ui-tests-ci.md PART 2 §(3)).
 *
 * Source of truth: `app/src/test/resources/contracts/openapi.json`, a trimmed copy of the
 * backend's committed OpenAPI export (trading-platform2 `audit/front/openapi.json`, 3.1,
 * ~490 paths), produced by `scripts/extract_openapi_contracts.py`. Re-run that script after
 * any backend contract change and commit the regenerated file — see docs/api-contracts.md
 * "Garde de drift".
 *
 * Four independent checks, each isolated in its own [Test] so one drift doesn't mask another:
 *  1. [`DTO fields exist in schema properties`] — every `@Json(name=...)` (fallback:
 *     constructor parameter name) on a DTO in [DTO_SCHEMA_TABLE] exists as a property of its
 *     mapped OpenAPI schema (`allOf` is followed; `$ref` is resolved by name).
 *  2. [`DTO non-nullable fields without defaults are backed by the schema`] — every DTO
 *     constructor parameter that is non-nullable AND has no Kotlin default must map to a
 *     schema property that is either in `required` or carries an explicit `default` (Pydantic
 *     serializes defaulted fields unconditionally, so they are just as "always present" as a
 *     required field even though OpenAPI does not list them under `required`). A violation
 *     here is a real Moshi crash risk: the backend can legally omit or null the field.
 *  3. [`Retrofit paths exist in the trimmed OpenAPI`] — every `@GET/@POST/@PUT/@DELETE/@PATCH`
 *     path declared on the `data/api/…Api` interfaces (via reflection on the Retrofit
 *     annotations — this test does NOT re-parse the .kt sources, so it stays in sync with the
 *     interfaces automatically) exists in `openapi.json`'s `paths`, modulo `{param}` NAME
 *     differences (Retrofit only requires its own `@Path` name to match its own annotation
 *     string, not the server's).
 *  4. [`WS event keys read by the app are backed by the actual backend payload`] — the set of
 *     JSON keys each WS event type consumer reads (hard-coded below, mirroring
 *     `data/repository/WsRepository.kt` / `data/websocket/PrivateWsClient.kt` /
 *     `data/websocket/PublicWsClient.kt`) is a subset of the keys the backend actually sends
 *     for that event (`app/src/test/resources/contracts/ws_events.json`, hand-maintained —
 *     WS payloads have no OpenAPI/AsyncAPI export in trading-platform2).
 *
 * This guard would have caught audit findings #6, #7 and #23 (market-data envelope shape,
 * WS position/portfolio payload field names) had it existed before those were fixed in PRs
 * 2.1/2.3. Writing it surfaced NEW, previously untracked drift — see the `PR-5c FINDING`
 * comments below and the PR report. Per the task boundary for this PR, main sources are NOT
 * modified to fix them; the corresponding assertions are therefore expected to fail (red)
 * until a follow-up PR addresses them.
 */
class DtoContractTest {

    // ── Schema resolution (openapi.json) ────────────────────────────────────────────────

    private data class SchemaInfo(val properties: Set<String>, val satisfied: Set<String>)

    private fun resolveSchema(
        schemas: JSONObject,
        name: String,
        seen: MutableSet<String> = mutableSetOf(),
    ): SchemaInfo {
        if (!seen.add(name)) return SchemaInfo(emptySet(), emptySet())
        val schema = schemas.optJSONObject(name)
            ?: error(
                "Schema '$name' not found in ${OPENAPI_RESOURCE} — re-run " +
                    "scripts/extract_openapi_contracts.py (it may need a new DTO_SCHEMA_TABLE entry " +
                    "or the backend openapi.json may be stale).",
            )
        val properties = mutableSetOf<String>()
        val satisfied = mutableSetOf<String>()

        fun mergeFrom(obj: JSONObject) {
            obj.optJSONObject("properties")?.let { props ->
                for (key in props.keys()) {
                    properties += key
                    if (props.getJSONObject(key).has("default")) satisfied += key
                }
            }
            obj.optJSONArray("required")?.let { arr ->
                for (i in 0 until arr.length()) satisfied += arr.getString(i)
            }
            obj.optJSONArray("allOf")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val sub = arr.getJSONObject(i)
                    val ref = sub.optString("\$ref", "")
                    if (ref.isNotEmpty()) {
                        val nested = resolveSchema(schemas, ref.substringAfterLast('/'), seen)
                        properties += nested.properties
                        satisfied += nested.satisfied
                    } else {
                        mergeFrom(sub)
                    }
                }
            }
        }
        mergeFrom(schema)
        return SchemaInfo(properties, satisfied)
    }

    // ── DTO reflection ───────────────────────────────────────────────────────────────────

    /** (jsonKey, isNonNullableWithoutDefault) for every primary-constructor parameter. */
    private fun dtoParams(kClass: KClass<*>): List<Pair<String, Boolean>> {
        val ctor = kClass.primaryConstructor
            ?: error("${kClass.simpleName} has no primary constructor")
        return ctor.parameters.map { param ->
            val jsonName = param.annotations.filterIsInstance<Json>().firstOrNull()?.name
            val key = jsonName ?: param.name ?: error("Unnamed parameter on ${kClass.simpleName}")
            val nonNullNoDefault = !param.type.isMarkedNullable && !param.isOptional
            key to nonNullNoDefault
        }
    }

    /**
     * DTO class -> OpenAPI schema name -> accepted exceptions (DTO json keys allowed to be
     * non-nullable-without-default even though the schema doesn't guarantee them — none used
     * here; see class KDoc on why PositionDto is deliberately NOT whitelisted).
     *
     * Skipped (schema not present in the source-of-truth file): TransactionDto,
     * TransactionListResponseDto — `GET /v1/portfolios/{portfolio_id}/transactions` is a
     * `response_model=TransactionListResponse, include_in_schema=False` Android-compat alias
     * (trading-platform2 app/portfolio/router.py:1081-1084) whose response schema is used by
     * no *included* path, so FastAPI drops `TransactionListResponse` from
     * `components.schemas` entirely — there is nothing to point at. ApiErrorDto,
     * TotpRequiredErrorDto — ad-hoc error bodies raised via `HTTPException`, never declared as
     * a Pydantic `response_model`, so they have no schema either.
     */
    private val DTO_SCHEMA_TABLE: List<Triple<KClass<*>, String, Set<String>>> = listOf(
        Triple(PositionDto::class, "PositionResponse", emptySet()),
        Triple(PnlResponseDto::class, "PnlResponse", emptySet()),
        Triple(PerformanceResponseDto::class, "PerformanceMetrics", emptySet()),
        Triple(QuoteDto::class, "QuoteResponse", emptySet()),
        Triple(SymbolListResponseDto::class, "SymbolListResponse", emptySet()),
        Triple(SymbolListItemDto::class, "SymbolListItem", emptySet()),
        Triple(MarketDataResponseDto::class, "MarketDataResponse", emptySet()),
        Triple(MarketDataPointDto::class, "MarketDataPoint", emptySet()),
        Triple(OrderDto::class, "OrderResponse", emptySet()),
        Triple(ActiveOrdersResponseDto::class, "ActiveOrdersResponse", emptySet()),
        Triple(OrderHistoryResponseDto::class, "OrderHistoryResponse", emptySet()),
        Triple(PortfolioCircuitBreakerStatusDto::class, "PortfolioCircuitBreakerStatus", emptySet()),
        Triple(DeviceDto::class, "DeviceResponse", emptySet()),
        Triple(DeviceListResponseDto::class, "DeviceListResponse", emptySet()),
        Triple(DeviceCommandRequestDto::class, "CommandRequest", emptySet()),
        Triple(VpnPeerDto::class, "VpnPeerDetailResponse", emptySet()),
        Triple(VpnPeerListResponseDto::class, "VpnPeerListResponse", emptySet()),
        Triple(BrokerConnectionDto::class, "DeviceBrokerConnectionResponse", emptySet()),
        Triple(FcmTokenRequestDto::class, "FcmTokenRegisterRequest", emptySet()),
        Triple(FcmTokenResponseDto::class, "FcmTokenRegisterResponse", emptySet()),
        Triple(LoginRequestDto::class, "LoginRequest", emptySet()),
        Triple(LoginResponseDto::class, "LoginResponse", emptySet()),
        // TokenResponseDto decodes /v1/auth/refresh too, whose actual response schema is named
        // "RefreshResponse" — same shape as "TokenResponse" field-for-field (both
        // access_token/refresh_token/token_type/expires_in, token_type defaulted "bearer" on
        // both). Mapped to "TokenResponse" since that's also the nested schema used by
        // LoginResponse.tokens / VerifyTwoFAResponse.tokens.
        Triple(TokenResponseDto::class, "TokenResponse", emptySet()),
        Triple(UserDto::class, "UserResponse", emptySet()),
        // /v1/auth/2fa/verify is include_in_schema=False (router.py:475-477, "Alias for
        // Android compatibility ... same handler as /verify-2fa") — same Verify2FARequest /
        // VerifyTwoFAResponse schemas as the included /v1/auth/verify-2fa route.
        Triple(TotpVerifyRequestDto::class, "Verify2FARequest", emptySet()),
        Triple(TotpVerifyResponseDto::class, "VerifyTwoFAResponse", emptySet()),
        Triple(WsTokenResponseDto::class, "WebSocketTokenResponse", emptySet()),
        Triple(PortfolioDto::class, "PortfolioResponse", emptySet()),
        Triple(PortfolioSummaryDto::class, "PortfolioResponse", emptySet()),
        Triple(PortfolioDetailDto::class, "PortfolioDetailResponse", emptySet()),
        Triple(PortfolioStrategyLinkDto::class, "PortfolioStrategyLink", emptySet()),
        Triple(BrokerSummaryDto::class, "BrokerSummary", emptySet()),
        // Unused dead code (no repository/UseCase constructs it) as of this PR, but still a
        // real DTO with a real backend counterpart — kept in the guard so it doesn't bit-rot
        // silently if it's wired up later.
        Triple(NavResponseDto::class, "NavResponse", emptySet()),
    )

    @Test
    fun `DTO fields exist in schema properties`() {
        val openApi = loadJson(OPENAPI_RESOURCE)
        val schemas = openApi.getJSONObject("components").getJSONObject("schemas")
        val violations = mutableListOf<String>()

        for ((kClass, schemaName, _) in DTO_SCHEMA_TABLE) {
            val schemaInfo = resolveSchema(schemas, schemaName)
            val missing = dtoParams(kClass)
                .map { it.first }
                .filter { it !in schemaInfo.properties }
            if (missing.isNotEmpty()) {
                violations += "${kClass.simpleName} -> $schemaName : missing from schema.properties: $missing"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Contract drift — DTO field(s) with no matching OpenAPI schema property " +
                "(renamed/removed backend field, or dead DTO field):\n" +
                violations.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `DTO non-nullable fields without defaults are backed by the schema`() {
        val openApi = loadJson(OPENAPI_RESOURCE)
        val schemas = openApi.getJSONObject("components").getJSONObject("schemas")
        val violations = mutableListOf<String>()

        for ((kClass, schemaName, accepted) in DTO_SCHEMA_TABLE) {
            val schemaInfo = resolveSchema(schemas, schemaName)
            val unsatisfied = dtoParams(kClass)
                .filter { (key, nonNullNoDefault) ->
                    nonNullNoDefault && key in schemaInfo.properties &&
                        key !in schemaInfo.satisfied && key !in accepted
                }
                .map { it.first }
            if (unsatisfied.isNotEmpty()) {
                violations += "${kClass.simpleName} -> $schemaName : " +
                    "non-nullable/no-default DTO field(s) not `required` and not `default`-ed " +
                    "in the schema (backend may legally omit or null them — Moshi crash risk): $unsatisfied"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Contract drift — DTO field(s) assumed always-present-and-non-null that the " +
                "backend schema does not guarantee:\n" +
                violations.joinToString("\n") { "  - $it" },
        )
    }

    // ── Retrofit path existence ──────────────────────────────────────────────────────────

    private val RETROFIT_METHOD_ANNOTATIONS: Set<Class<out Annotation>> = setOf(
        retrofit2.http.GET::class.java,
        retrofit2.http.POST::class.java,
        retrofit2.http.PUT::class.java,
        retrofit2.http.DELETE::class.java,
        retrofit2.http.PATCH::class.java,
    )

    /**
     * `data/api/…Api` interfaces whose endpoints talk to the VPS and are therefore checked
     * against `openapi.json`. `PairingLanApi` is excluded — it talks to the Radxa over the
     * LAN via a fully dynamic `@Url` (no literal path, no backend OpenAPI contract; see
     * CLAUDE.md §8 "Repository LAN").
     */
    private val BACKEND_API_INTERFACES: List<KClass<*>> = listOf(
        AuthApi::class,
        PortfolioApi::class,
        MarketDataApi::class,
        DeviceApi::class,
        OrdersApi::class,
        NotificationApi::class,
        MyDevicesApi::class,
        BrokerConnectionApi::class,
        RiskApi::class,
        StrategiesApi::class,
    )

    private fun normalizePath(path: String) = if (path.startsWith("/")) path else "/$path"

    private fun pathTemplateKey(path: String) = Regex("\\{[^/{}]+\\}").replace(path, "{}")

    /** (method, normalized path, "Interface.method" for error messages). */
    private fun retrofitEndpoints(): List<Triple<String, String, String>> {
        val endpoints = mutableListOf<Triple<String, String, String>>()
        for (api in BACKEND_API_INTERFACES) {
            for (method in api.java.methods) {
                for (annotationClass in RETROFIT_METHOD_ANNOTATIONS) {
                    // .annotations.firstOrNull { isInstance } rather than the generic
                    // getAnnotation(Class<T>) overload — avoids relying on Kotlin's handling
                    // of a captured wildcard type (RETROFIT_METHOD_ANNOTATIONS is
                    // Set<Class<out Annotation>>) as the reified T of a Java generic method.
                    val annotation = method.annotations.firstOrNull { annotationClass.isInstance(it) }
                        ?: continue
                    val value = annotationClass.getMethod("value").invoke(annotation) as String
                    if (value.isBlank()) continue // e.g. bare @GET with @Url — not applicable here
                    endpoints += Triple(
                        annotationClass.simpleName!!.uppercase(),
                        normalizePath(value),
                        "${api.simpleName}.${method.name}",
                    )
                }
            }
        }
        return endpoints
    }

    /**
     * Retrofit endpoints the app calls that are deliberately NOT keys of `openapi.json`'s
     * `paths` (see scripts/extract_openapi_contracts.py's SCHEMA_ONLY_ALIASES / the
     * TransactionDto skip note above) — both are `include_in_schema=False` Android-compat
     * aliases on the backend, confirmed against trading-platform2 source, not accidental gaps.
     */
    private val ACCEPTED_MISSING_PATHS: Set<Pair<String, String>> = setOf(
        "POST" to "/v1/auth/2fa/verify", // router.py:475-477, alias of /verify-2fa
        "GET" to "/v1/portfolios/{portfolio_id}/transactions", // router.py:1081-1084, alias of /trades
    )

    @Test
    fun `Retrofit paths exist in the trimmed OpenAPI`() {
        val openApi = loadJson(OPENAPI_RESOURCE)
        val paths = openApi.getJSONObject("paths")
        val templateKeys = paths.keys().asSequence().map { pathTemplateKey(it) }.toSet()
        val acceptedTemplated = ACCEPTED_MISSING_PATHS.map { it.first to pathTemplateKey(it.second) }.toSet()

        val violations = mutableListOf<String>()
        for ((method, path, source) in retrofitEndpoints()) {
            val accepted = (method to path) in ACCEPTED_MISSING_PATHS ||
                (method to pathTemplateKey(path)) in acceptedTemplated
            if (accepted) continue
            val exists = path in paths.keys().asSequence().toSet() || pathTemplateKey(path) in templateKeys
            if (!exists) {
                violations += "$method $path  ($source)"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Contract drift — Retrofit endpoint(s) with no matching path in $OPENAPI_RESOURCE " +
                "(re-run scripts/extract_openapi_contracts.py if the backend added the route " +
                "after this file was last regenerated):\n" +
                violations.joinToString("\n") { "  - $it" },
        )
    }

    // ── WS event key coverage ─────────────────────────────────────────────────────────────

    /**
     * Keys each WS event type consumer reads, hard-coded here mirroring:
     *  - data/repository/WsRepository.kt (portfolio_update, position_update, order_update,
     *    notification, strategy_signal, catalyst_event — the private channel)
     *  - data/websocket/PrivateWsClient.kt (the `notification` envelope's notifType/title/body
     *    extraction, lines ~444-448)
     *  - data/websocket/PublicWsClient.kt#parseMarketData (market_data — the public channel)
     *
     * A handful of keys are read but deliberately excluded from the subset check below
     * because they are ALREADY documented in the app source as intentionally-always-null
     * placeholders, not drift:
     *  - portfolio_update: `daily_pnl`, `total_pnl` — WsRepository.kt:33-37 KDoc: "ne sont
     *    jamais envoyés par ce canal et restent null".
     *  - position_update: `position_id` — WsRepository.kt:60-62 KDoc: "ne porte pas de
     *    position_id ... reste null tant que le backend ne l'ajoute pas". `current_price` —
     *    WsRepository.kt:63-64 KDoc: legacy-format fallback, "conservé en fallback pour
     *    tolérer un éventuel ancien payload".
     * Every other key below is a genuine assertion — see ws_events.json's `notes` for the
     * ones that currently fail (PR-5c findings, not fixed here).
     */
    private val APP_WS_READ_KEYS: Map<String, Set<String>> = mapOf(
        "portfolio_update" to setOf(
            "portfolio_id", "symbol", "side", "quantity", "price",
            "total_value", "cash_balance", "positions_value",
        ),
        "position_update" to setOf(
            "symbol", "side", "quantity", "average_price", "last_price",
            "unrealized_pnl", "realized_pnl", "is_active",
        ),
        "order_update" to setOf(
            "order_id", "symbol", "side", "status", "quantity", "fill_price",
        ),
        "notification" to setOf(
            "type", "title", "body", "message",
        ),
        "strategy_signal" to setOf(
            "signal_id", "strategy_id", "symbol", "action", "confidence", "strategy_type",
        ),
        "catalyst_event" to setOf(
            "symbol", "event_type", "title", "description",
        ),
        "market_data" to setOf(
            "symbol", "price", "open", "high", "low", "close", "volume", "bid", "ask",
            "source_name", "source_type", "quality", "data_mode",
        ),
    )

    @Test
    fun `WS event keys read by the app are backed by the actual backend payload`() {
        val wsEvents = loadJson(WS_EVENTS_RESOURCE)
        val violations = mutableListOf<String>()

        for ((eventType, readKeys) in APP_WS_READ_KEYS) {
            val eventSpec = wsEvents.optJSONObject(eventType)
                ?: error("Event '$eventType' missing from $WS_EVENTS_RESOURCE")
            val backendKeys = eventSpec.getJSONArray("keys").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
            val missing = readKeys - backendKeys
            if (missing.isNotEmpty()) {
                violations += "$eventType : app reads $missing, backend never sends them " +
                    "(source: ${eventSpec.optString("source", "?")})"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Contract drift — WS event key(s) the app reads that the backend never sends " +
                "(field will silently and permanently decode to null):\n" +
                violations.joinToString("\n") { "  - $it" },
        )
    }

    private companion object {
        const val OPENAPI_RESOURCE = "/contracts/openapi.json"
        const val WS_EVENTS_RESOURCE = "/contracts/ws_events.json"

        fun loadJson(resourcePath: String): JSONObject {
            val stream = DtoContractTest::class.java.getResourceAsStream(resourcePath)
                ?: error(
                    "Missing test resource $resourcePath — run " +
                        "scripts/extract_openapi_contracts.py (openapi.json) or check " +
                        "app/src/test/resources/contracts/ (ws_events.json, hand-maintained).",
                )
            return JSONObject(stream.bufferedReader().readText())
        }
    }
}
