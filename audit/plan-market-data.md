# Plan partiel — données de marché (#6, #7, #13, #14, #23, volume)
Source : agent planificateur (Sonnet/Plan), 2026-09-28. APP = app/src/main/java/com/tradingplatform/app/, BE = trading-platform2/.

Décision : tout côté app, sauf un ajout additif backend (`position_id` dans le payload `position_update`). Raison : le backend expose déjà un contrat riche et paginé consommé par le web ; un alias « Android » figerait une forme appauvrie. Mettre à jour docs/api-contracts.md:263-290.

## A. #6 /v1/market-data/symbols (S)
Backend router.py:232-259, schemas.py:382-399 : query search/exchange/currency/index_sid/limit(≤500, def 100)/offset → {symbols:[{sid,ticker,name,exchange?,currency?,is_active}], total, limit, offset, has_more}.
Fichiers : MarketDataApi.kt:14-15, nouveau data/model/SymbolListDto.kt, MarketDataRepositoryImpl.kt:84-90, domain/repository/MarketDataRepository.kt:8, GetAvailableSymbolsUseCase, MarketDataViewModel.kt:44-49,119-131, SymbolPickerSheet.kt.
Approche : DTO complet ; domaine SymbolInfo(ticker,name,exchange?,currency?) + SymbolPage(items,hasMore,nextOffset) ; recherche serveur `search=` avec debounce 300 ms ; pagination offset par « charger plus » ; supprimer le filtre local SymbolPickerSheet.kt:167-175 ; key = ticker.
```kotlin
@GET("v1/market-data/symbols")
suspend fun getSymbols(@Query("search") search: String? = null, @Query("limit") limit: Int = 100, @Query("offset") offset: Int = 0): Response<SymbolListResponseDto>
data class SymbolListItemDto(sid:Int, ticker:String, name:String, exchange:String?=null, currency:String?=null, @Json("is_active") isActive:Boolean=true)
data class SymbolListResponseDto(symbols:List<SymbolListItemDto>, total:Int, limit:Int, offset:Int, @Json("has_more") hasMore:Boolean)
```
Tests : décodage fixture JSON copiée du schéma ; MarketDataViewModelTest (debounce → 1 appel, loadMore → offset, is_active=false filtré).

## B. #7 sparklines (S)
Backend : /{symbol}/history exige start/end, param `timeframe`, réponse MarketDataResponse{symbol,data:[MarketDataPoint{timestamp,open,high,low,close,volume?,...}],count,timeframe,next_cursor}. Moins cher : `GET /v1/market-data/?symbol=X&timeframe=1d&limit=30` (router.py:168-192 get_latest), renvoie DESC (service.py:369) → asReversed(). Recommandé. Si history : start = now-45j, end = now (ISO_INSTANT).
```kotlin
@GET("v1/market-data/") suspend fun getLatestBars(@Query("symbol") symbol: String, @Query("timeframe") timeframe: String = "1d", @Query("limit") limit: Int = 30): Response<MarketDataResponseDto>
data class MarketDataResponseDto(symbol:String, data:List<MarketDataPointDto>, count:Int, @Json("next_cursor") nextCursor:String?=null)
// repo: body.data.map { it.close }.asReversed()
```
Vérifier que FastAPI n'émet pas de 307 sur le slash final (redirect_slashes) ; sinon utiliser history. SparklineChart exige ≥2 points (MarketDataScreen.kt:421).

## C. #23 position_update (S, app + 1 ligne backend)
Backend consumer.py:2172-2199 : portfolio_id, symbol, side, quantity, average_price, last_price, unrealized_pnl, realized_pnl, is_active (pas d'id). Backend : ajouter `"position_id": position.get("id")` si présent (additif). App : ne pas en dépendre.
Fichiers : domain/model/WsUpdate.kt:27-33, WsRepository.kt:42-49, PositionsViewModel.kt:66-97.
```kotlin
data class PositionUpdate(positionId:String?=null, symbol:String?=null, side:String?=null, quantity:Double?=null, averagePrice:Double?=null, lastPrice:Double?=null, unrealizedPnl:Double?=null, realizedPnl:Double?=null, isActive:Boolean=true)
// mapping: lastPrice = opt("last_price") ?: opt("current_price"); isActive = !has("is_active") || optBoolean("is_active", true)
private fun matchesPosition(p: Position, u: PositionUpdate): Boolean { u.positionId?.let { return it == p.id.toString() }; return p.status == OPEN && u.symbol == p.symbol }
// merge: !isActive → retirer sous OPEN / marquer CLOSED sous ALL ; sinon copy(currentPrice=lastPrice, unrealizedPnl, quantity)
```
Drift supplémentaire trouvé — `portfolio_update` : backend envoie portfolio_id, symbol, side, quantity, price, total_value, cash_balance, positions_value ; l'app lit nav/daily_pnl/total_pnl (WsRepository.kt:32-39) → toujours null ; GetActivityFeedUseCase.kt:54-59 affiche nav=null. Correctif : nav = total_value, ajouter symbol/side/quantity/price/cashBalance ; dailyPnl reste null (ne pas l'afficher).
Tests : PositionsViewModelTest (update TSLA sous ALL ne touche que la ligne OPEN ; is_active=false retire ; last_price → currentPrice) ; WsRepositoryTest avec JSON copié de consumer.py.

## D. #13/#14 ref-count + connectionState public + fallback REST (L)
Fichiers : PublicWsClient.kt:79,148-166,280-287, PublicWsRepositoryImpl.kt, domain/repository/PublicWsRepository.kt, nouveau GetPublicWsConnectionStateUseCase.kt, nouveau ui/common/QuoteFallbackController.kt, MarketDataViewModel.kt:183-260, DashboardViewModel.kt:329-400, di/WebSocketModule.kt.
1. Ref-count : ConcurrentHashMap<String,Int> sous synchronized(lock) ; subscribe envoie le frame seulement 0→1, unsubscribe seulement 1→0 ; resubscribe à onOpen sur keys.
2. État : `_connectionState = MutableStateFlow(WsConnectionState.Disconnected)` (même pattern que PrivateWsClient.kt:80-81) : Connecting dans openWebSocket, Connected onOpen, Disconnected onClosed/onFailure, Degraded si reconnectAttempts ≥ 3. Exposer via PublicWsRepository + use case. Ajouter la garde `if (e is CancellationException) throw e` dans openWebSocket.
3. Helper partagé QuoteFallbackController(scope, state, stream, fetch, onQuote, onStale) : le flux WS reste toujours collecté (jamais annulé → resouscription automatique) ; `state.map{it==Connected}.distinctUntilChanged().collectLatest { up -> if (!up) { onStale(symbol); while(isActive){ fetch(symbol).onSuccess(onQuote); delay(30_000) } } }`. Supprime les 3 copies et les catch(Exception). Debounce 2 s sur Disconnected (cf. DashboardViewModel.kt:149-160). Ne poller que si foreground (ProcessLifecycleOwner).
Tests : PublicWsClientTest (MockWebServer WS : 2 subscribe/1 unsubscribe → aucun frame unsubscribe ; 2e → frame + close) ; QuoteFallbackControllerTest (Turbine + StandardTestDispatcher).

## E. volume (S)
PublicWsClient.kt:357 : `data.optString("volume","").toBigDecimalOrNull()?.toLong() ?: 0L` ; test parseMarketData("123456.0") → 123456.

## Ordre / effort
E (10 min) → A (S) → B (S) → C (S) → D (L, 3 commits : ref-count → connectionState → helper+VMs). ≈ 2 jours. Docs : CLAUDE.md §2 (garde CancellationException, fallback piloté par connectionState), docs/api-contracts.md:263-290.
