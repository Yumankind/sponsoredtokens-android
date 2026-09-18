package com.sponsoredtokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * THE ONE CONVERSION THAT STANDS BETWEEN ANDROID AND THE POOL, tested against real signatures.
 *
 * The AndroidKeyStore signs with `SHA256withECDSA`, which emits DER; the worker's verifier refuses
 * anything that is not the 64-byte raw `r‖s` form by length. A hand-written vector could never tell
 * us whether the conversion kept the mathematics, so these generate real P-256 keys with the JVM's
 * own provider - the same `java.security` API the Keystore implements - sign real messages, convert,
 * and then convert BACK and make the platform verify it. What survives that round trip is what the
 * worker's `crypto.subtle.verify` sees.
 */
class DerTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()

    private fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(message)
        }.sign()

    private fun verify(message: ByteArray, der: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").apply {
            initVerify(keyPair.public)
            update(message)
        }.verify(der)

    @Test
    fun `a real signature converts to exactly 64 bytes`() {
        // A hundred of them, because the DER encoding's length varies with whether the top bit of r
        // or s happens to be set, and a conversion that only works for the common case is a
        // conversion that fails a few times a day in production.
        repeat(100) {
            val message = "POST\n/api/v1/chat/completions\n175734000$it\n${Signing.EMPTY_BODY_SHA256}".toByteArray()
            val raw = Der.toRaw(sign(message))
            assertEquals("signature $it was not 64 bytes", 64, raw.size)
        }
    }

    @Test
    fun `raw round trips back to something the platform still verifies`() {
        repeat(100) {
            val message = "GET\n/api/v1/models\n1757340000\n${Signing.EMPTY_BODY_SHA256}\n$it".toByteArray()
            val raw = Der.toRaw(sign(message))
            assertTrue("round trip $it did not verify", verify(message, Der.fromRaw(raw)))
        }
    }

    @Test
    fun `a signature does not verify against a different canonical string`() {
        val signed = "POST\n/api/v1/models?tier=0\n1757340000\n${Signing.EMPTY_BODY_SHA256}".toByteArray()
        val other = "POST\n/api/v1/models?tier=3\n1757340000\n${Signing.EMPTY_BODY_SHA256}".toByteArray()
        val raw = Der.toRaw(sign(signed))
        assertTrue(verify(signed, Der.fromRaw(raw)))
        assertTrue("a signature for one query verified against another", !verify(other, Der.fromRaw(raw)))
    }

    @Test
    fun `a small component is left padded rather than shortening the signature`() {
        // r = 1 is minimally encoded as a single byte. It must land in the LAST byte of the first
        // 32, not the first, or every verification of it fails on a value that is arithmetically
        // correct.
        val der = Der.fromRaw(ByteArray(64).also { it[31] = 1; it[63] = 2 })
        val raw = Der.toRaw(der)
        assertEquals(64, raw.size)
        assertEquals(1, raw[31].toInt())
        assertEquals(2, raw[63].toInt())
        assertEquals(0, raw[0].toInt())
        assertEquals(0, raw[32].toInt())
    }

    @Test
    fun `a component with the top bit set survives the DER sign byte`() {
        // A DER INTEGER is signed, so a component whose first byte is >= 0x80 is encoded with a
        // leading zero. Stripping it is `copyRight`'s job, and getting it wrong shifts every byte.
        val raw = ByteArray(64)
        val r = BigInteger("f".repeat(64), 16).toFixedWidth(32)
        System.arraycopy(r, 0, raw, 0, 32)
        raw[63] = 9
        val back = Der.toRaw(Der.fromRaw(raw))
        assertTrue(raw.contentEquals(back))
    }
}
