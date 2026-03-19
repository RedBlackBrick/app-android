# FCM — Notes d'implémentation

Firebase Cloud Messaging pour les notifications push de trading.

## Côté app Android

1. `google-services.json` (depuis Firebase Console) dans `app/`
2. Dépendances : `firebase-messaging-ktx`, `firebase-analytics-ktx`
3. `TradingFirebaseMessagingService : FirebaseMessagingService`
4. `onNewToken()` envoie le token FCM au backend via `RegisterFcmTokenUseCase` → `POST /v1/notifications/fcm-token` (body : `{fcm_token, device_fingerprint}`)

## Côté VPS (trading-platform)

1. Endpoint `POST /v1/notifications/fcm-token` dans le module `notification`
2. Token FCM stocké en DB par utilisateur
3. Le module `notification` envoie vers l'API FCM lors des alertes critiques

## Types d'alertes

- SL/TP déclenché sur une position
- Signal fort généré par le strategy-engine
- Device Radxa offline > 5 minutes
- Erreur critique sur le VPS (circuit breaker ouvert)
- OTA disponible pour les devices
