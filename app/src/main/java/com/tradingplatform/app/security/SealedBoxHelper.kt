package com.tradingplatform.app.security

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
    private val sodium: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

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
