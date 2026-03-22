package com.tradingplatform.app.data.local.datastore

/**
 * Distingue les 3 cas de lecture depuis [EncryptedDataStore] (R1 fix).
 *
 * Le type est volontairement simple et non-générique pour rester lisible.
 * Utilisé uniquement par les méthodes `readStringSafe()` / `readLongSafe()` etc.
 * Les méthodes existantes `readString()`, `readLong()` ne sont pas modifiées
 * pour rester backward-compatible (la majorité des lectures n'ont pas besoin
 * de distinguer "absent" de "corrompu").
 *
 * ## Usage
 * ```kotlin
 * when (val result = dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN)) {
 *     is SecureReadResult.Found -> useToken(result.value)
 *     is SecureReadResult.NotFound -> navigateToLogin()
 *     is SecureReadResult.Corrupted -> showCorruptionDialog(result.cause)
 * }
 * ```
 */
sealed interface SecureReadResult<out T> {

    /** La valeur existe et a été lue avec succès. */
    data class Found<T>(val value: T) : SecureReadResult<T>

    /** La clé n'existe pas dans le store (valeur jamais écrite ou supprimée). */
    data object NotFound : SecureReadResult<Nothing>

    /**
     * Erreur de lecture : fichier corrompu (IOException) ou Keystore invalidé
     * (GeneralSecurityException — reboot, suppression biométrie, reset device).
     *
     * L'UI doit afficher un message explicite et proposer de se reconnecter.
     */
    data class Corrupted(val cause: Throwable) : SecureReadResult<Nothing>
}

/**
 * Extension pour convertir un [SecureReadResult] en valeur nullable,
 * gardant la compatibilité avec le code existant qui attend `String?`.
 *
 * Usage : `dataStore.readStringSafe(key).valueOrNull()`
 */
fun <T> SecureReadResult<T>.valueOrNull(): T? = when (this) {
    is SecureReadResult.Found -> value
    is SecureReadResult.NotFound -> null
    is SecureReadResult.Corrupted -> null
}

/**
 * Retourne true si la lecture a échoué à cause d'une corruption/Keystore invalidé.
 */
fun <T> SecureReadResult<T>.isCorrupted(): Boolean = this is SecureReadResult.Corrupted
