package com.tradingplatform.app.security

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.Box
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Exercises [SealedBoxHelper] with **real libsodium crypto** on the plain JVM unit test runner.
 *
 * Production code hard-instantiates the Android JNI-backed `LazySodiumAndroid`, which cannot
 * load its native `.so` outside an Android device/emulator. [SealedBoxHelper] gained a minimal
 * internal constructor seam (`internal constructor(sodium: LazySodium)`) for this test file only
 * — it lets a JVM/JNA-backed `LazySodiumJava` stand in for `LazySodiumAndroid`. Both extend the
 * same `com.goterl.lazysodium.LazySodium` base class, which is where `cryptoBoxSeal` /
 * `cryptoBoxSealOpen` are actually implemented — so this is a faithful test of the real
 * `crypto_box_seal` primitive `SealedBoxHelper.seal()` calls, not a mock.
 *
 * Relevant in the pairing flow (CLAUDE.md §8): the app seals `session_pin` + `local_token` +
 * `nonce` with the Radxa's WireGuard public key (Curve25519) before sending it over the LAN.
 */
class SealedBoxHelperRealTest {

    private lateinit var sodium: LazySodiumJava
    private lateinit var helper: SealedBoxHelper

    @Before
    fun setUp() {
        sodium = LazySodiumJava(SodiumJava())
        helper = SealedBoxHelper(sodium)
    }

    @Test
    fun `seal output length is plaintext length plus Box SEALBYTES (48)`() {
        val keyPair = sodium.cryptoBoxKeypair()
        val plaintext = "session_pin=472938".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = helper.seal(plaintext, keyPair.publicKey.asBytes)

        assertEquals(48, Box.SEALBYTES)
        assertEquals(plaintext.size + 48, ciphertext.size)
    }

    @Test
    fun `seal produces a different ciphertext on every call (random ephemeral key + nonce)`() {
        val keyPair = sodium.cryptoBoxKeypair()
        val plaintext = "session_pin=472938".toByteArray(StandardCharsets.UTF_8)

        val ciphertext1 = helper.seal(plaintext, keyPair.publicKey.asBytes)
        val ciphertext2 = helper.seal(plaintext, keyPair.publicKey.asBytes)

        assertFalse(
            "crypto_box_seal must not be deterministic — reusing ciphertexts would leak equality",
            ciphertext1.contentEquals(ciphertext2),
        )
    }

    @Test
    fun `cryptoBoxSealOpen with the matching keypair recovers the original plaintext`() {
        val keyPair = sodium.cryptoBoxKeypair()
        val plaintext = "session_pin=472938;nonce=deadbeef".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = helper.seal(plaintext, keyPair.publicKey.asBytes)

        val decrypted = ByteArray(plaintext.size)
        val opened = sodium.cryptoBoxSealOpen(
            decrypted,
            ciphertext,
            ciphertext.size.toLong(),
            keyPair.publicKey.asBytes,
            keyPair.secretKey.asBytes,
        )

        assertTrue("crypto_box_seal_open should succeed with the matching keypair", opened)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `cryptoBoxSealOpen fails with the wrong secret key`() {
        val recipientKeyPair = sodium.cryptoBoxKeypair()
        val attackerKeyPair = sodium.cryptoBoxKeypair()
        val plaintext = "top secret payload".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = helper.seal(plaintext, recipientKeyPair.publicKey.asBytes)

        val decrypted = ByteArray(plaintext.size)
        val opened = sodium.cryptoBoxSealOpen(
            decrypted,
            ciphertext,
            ciphertext.size.toLong(),
            recipientKeyPair.publicKey.asBytes,
            attackerKeyPair.secretKey.asBytes,
        )

        assertFalse("crypto_box_seal_open must fail (MAC mismatch) with a non-matching secret key", opened)
    }

    @Test
    fun `seal throws IllegalArgumentException when the public key is not 32 bytes`() {
        val wrongSizeKey = ByteArray(16) // Curve25519 public keys are Box.PUBLICKEYBYTES = 32 bytes
        val plaintext = "hello".toByteArray(StandardCharsets.UTF_8)

        assertEquals(32, Box.PUBLICKEYBYTES)
        assertThrows(IllegalArgumentException::class.java) {
            helper.seal(plaintext, wrongSizeKey)
        }
    }

    @Test
    fun `seal throws IllegalArgumentException on an empty public key`() {
        val plaintext = "hello".toByteArray(StandardCharsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) {
            helper.seal(plaintext, ByteArray(0))
        }
    }
}
