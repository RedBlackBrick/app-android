package com.tradingplatform.app.security

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper libsodium crypto_box_seal pour chiffrement asymétrique anonyme.
 *
 * Utilise la clé publique Curve25519 du Radxa (= sa wg_pubkey) pour chiffrer.
 * Seul le Radxa peut déchiffrer avec sa clé privée.
 *
 * Usage :
 *   val encrypted = sealedBoxHelper.seal(jsonBytes, radxaWgPubkeyBytes)
 *   // envoyer encrypted en octet-stream au Radxa
 */
@Singleton
class SealedBoxHelper @Inject constructor() {
    private var sodium: LazySodium = LazySodiumAndroid(SodiumAndroid())

    /**
     * Test-only seam (PR 5b, audit/plan-ui-tests-ci.md) — substitutes the Android JNI-backed
     * [LazySodiumAndroid] with any other [LazySodium] implementation (e.g. `LazySodiumJava`),
     * so `crypto_box_seal` / `crypto_box_seal_open` can be exercised with real libsodium crypto
     * on the plain JVM unit test runner (Android's native `.so` cannot load there). Production
     * code always goes through the zero-arg `@Inject` constructor above; Hilt never sees this one.
     */
    internal constructor(sodium: LazySodium) : this() {
        this.sodium = sodium
    }

    /**
     * Chiffre [plaintext] avec la [recipientPublicKey] (32 bytes Curve25519).
     * Retourne les bytes chiffrés (plaintext.size + Box.SEALBYTES).
     */
    fun seal(plaintext: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        require(recipientPublicKey.size == Box.PUBLICKEYBYTES) {
            "Invalid public key size: ${recipientPublicKey.size} (expected ${Box.PUBLICKEYBYTES})"
        }
        val ciphertext = ByteArray(plaintext.size + Box.SEALBYTES)
        val success = sodium.cryptoBoxSeal(ciphertext, plaintext, plaintext.size.toLong(), recipientPublicKey)
        check(success) { "crypto_box_seal failed" }
        return ciphertext
    }
}
