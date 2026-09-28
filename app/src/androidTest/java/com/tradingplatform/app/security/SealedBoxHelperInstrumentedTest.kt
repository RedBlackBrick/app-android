package com.tradingplatform.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

/**
 * Instrumented test for [SealedBoxHelper] against the real libsodium native library (loaded via
 * JNA on-device — see the `net.java.dev.jna:jna:...@aar` note in app/build.gradle.kts). This
 * cannot run as a JVM unit test: `SodiumAndroid()` needs `libsodium.so`/`libjnidispatch.so`
 * resolved for the device's actual ABI (arm64-v8a / armeabi-v7a on a real device, x86_64 on the
 * Gradle Managed Device emulators).
 *
 * Round-trips through `crypto_box_seal` / `crypto_box_seal_open` (LazySodium's `Box.Native`
 * interface) using a freshly generated Curve25519 keypair — [SealedBoxHelper] only exposes
 * `seal()` (the Radxa side owns `crypto_box_seal_open` with its private key), so this test
 * plays that Radxa role itself to prove the ciphertext is genuinely decryptable and not, say,
 * an accidental no-op or XOR stub.
 */
@RunWith(AndroidJUnit4::class)
class SealedBoxHelperInstrumentedTest {

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    @Test
    fun seal_outputLength_isPlaintextPlusSealbytes() {
        val helper = SealedBoxHelper()
        val (publicKey, _) = generateKeyPair()
        val plaintext = "pairing-payload-session_pin+nonce".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = helper.seal(plaintext, publicKey)

        assertEquals(plaintext.size + Box.SEALBYTES, ciphertext.size)
    }

    @Test
    fun seal_isDecryptableByTheRecipientPrivateKey_andYieldsTheOriginalPlaintext() {
        val helper = SealedBoxHelper()
        val (publicKey, secretKey) = generateKeyPair()
        val plaintext = "{\"session_pin\":\"472938\",\"nonce\":\"deadbeef\"}"
            .toByteArray(StandardCharsets.UTF_8)

        val ciphertext = helper.seal(plaintext, publicKey)

        val decrypted = ByteArray(plaintext.size)
        val opened = sodium.cryptoBoxSealOpen(
            decrypted,
            ciphertext,
            ciphertext.size.toLong(),
            publicKey,
            secretKey,
        )

        assertTrue("crypto_box_seal_open should succeed with the matching secret key", opened)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun seal_rejectsAPublicKeyOfTheWrongSize() {
        val helper = SealedBoxHelper()
        val plaintext = "x".toByteArray(StandardCharsets.UTF_8)

        try {
            helper.seal(plaintext, ByteArray(Box.PUBLICKEYBYTES - 1))
            throw AssertionError("expected an IllegalArgumentException for a malformed public key")
        } catch (e: IllegalArgumentException) {
            // expected — SealedBoxHelper.seal() `require`s the exact key size
        }
    }

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val publicKey = ByteArray(Box.PUBLICKEYBYTES)
        val secretKey = ByteArray(Box.SECRETKEYBYTES)
        val ok = sodium.cryptoBoxKeypair(publicKey, secretKey)
        assertTrue("cryptoBoxKeypair should succeed", ok)
        return publicKey to secretKey
    }
}
