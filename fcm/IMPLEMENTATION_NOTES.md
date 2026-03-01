# FCM — Notes d'implémentation (future)

Firebase Cloud Messaging pour les notifications push de trading.

## Ce qu'il faudra faire

### Côté app Android
1. Ajouter `google-services.json` (depuis Firebase Console) dans `app/`
2. Dépendances : `firebase-messaging-ktx`, `firebase-analytics-ktx`
3. Créer `TradingFirebaseMessagingService : FirebaseMessagingService`
4. Transmettre le token FCM au VPS à chaque connexion via `POST /v1/devices/fcm-token`

### Côté VPS (trading-platform)
1. Ajouter un endpoint dans `api-gateway` ou `notification` : `POST /v1/devices/fcm-token`
2. Stocker le token FCM en DB par utilisateur
3. Modifier `notification` service pour envoyer vers l'API FCM lors des alertes critiques

## Types d'alertes prévus
- SL/TP déclenché sur une position
- Signal fort généré par le strategy-engine
- Device Radxa offline > 5 minutes
- Erreur critique sur le VPS (circuit breaker ouvert)
- OTA disponible pour les devices
