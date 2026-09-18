package com.sponsoredtokens

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * THE JWK THE WORKER WILL ACCEPT, and nothing that it will not.
 *
 * `worker/src/sponsored/app-devices.ts::normalizeDeviceJwk` keeps exactly four fields, refuses any
 * curve but P-256, refuses a JWK carrying a private `d`, and refuses a coordinate outside 42 to 44
 * base64url characters. These assertions are that function read backwards. Nothing here touches the
 * AndroidKeyStore: the keys are the JVM provider's own, through the same `java.security` API the
 * Keystore implements.
 */
class DeviceKeyTest {

    private fun p256(): ECPublicKey = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair().public as ECPublicKey

    @Test
    fun `a JWK is four fields, in the worker's own order, with no whitespace`() {
        val jwk = DeviceKey.jwkOf(p256())
        assertTrue(jwk.startsWith("""{"kty":"EC","crv":"P-256","x":""""))
        assertFalse("a JWK with whitespace is a longer URL for no reason", jwk.contains(' '))
        val parsed = JSONObject(jwk)
        assertEquals(setOf("kty", "crv", "x", "y"), parsed.keys().asSequence().toSet())
        assertEquals("EC", parsed.getString("kty"))
        assertEquals("P-256", parsed.getString("crv"))
        // NEVER THE PRIVATE HALF. The worker refuses a registration carrying one outright rather
        // than dropping it, and there is nothing here that could produce one.
        assertFalse(parsed.has("d"))
    }

    @Test
    fun `every coordinate is 43 base64url characters, a hundred keys running`() {
        // The worker's own range is 42 to 44, because a padded or trimmed encoder is a client bug
        // and not an attack. A fixed-width 32-byte coordinate is always exactly 43.
        repeat(100) {
            val parsed = JSONObject(DeviceKey.jwkOf(p256()))
            for (field in listOf("x", "y")) {
                val value = parsed.getString(field)
                assertEquals("coordinate $field of key $it was ${value.length} characters", 43, value.length)
                assertTrue(value.matches(Regex("^[A-Za-z0-9_-]{43}$")))
            }
        }
    }

    @Test
    fun `a small coordinate is left padded rather than shortened`() {
        // `BigInteger.toByteArray` gives one byte for 1, and 33 for anything whose top bit is set.
        // A JWK coordinate is a FIXED WIDTH field, and both of those are the wrong number.
        assertEquals(32, BigInteger.ONE.toFixedWidth(32).size)
        assertEquals(1, BigInteger.ONE.toFixedWidth(32)[31].toInt())
        assertEquals(0, BigInteger.ONE.toFixedWidth(32)[0].toInt())

        val topBitSet = BigInteger("f".repeat(64), 16)
        assertEquals(33, topBitSet.toByteArray().size)
        assertEquals(32, topBitSet.toFixedWidth(32).size)
        assertEquals(-1, topBitSet.toFixedWidth(32)[0].toInt())
    }

    @Test
    fun `the device_key parameter is the JWK, base64url, and decodes back to it`() {
        val jwk = DeviceKey.jwkOf(p256())
        val param = DeviceKey.deviceKeyParam(jwk)
        assertTrue(param.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertEquals(jwk, String(B64.urlDecode(param), Charsets.UTF_8))
    }

    @Test
    fun `the alias is namespaced by client id, so two apps are two keys`() {
        assertEquals("com.sponsoredtokens.device.app_a", AndroidKeystoreDeviceKey.aliasFor("app_a"))
        assertTrue(AndroidKeystoreDeviceKey.aliasFor("app_a") != AndroidKeystoreDeviceKey.aliasFor("app_b"))
    }
}
