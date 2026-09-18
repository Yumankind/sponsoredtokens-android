package com.sponsoredtokens

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A KEY THAT NEVER LEAVES THE DEVICE, which is the whole of the credential.
 *
 * An app key in an APK is an app key in every copy of that APK, and a bearer in shared preferences
 * is a bearer anything with a backup agent can read. So a native caller identifies itself the way
 * developer apps §24 says a browser does: an ECDSA P-256 pair whose private half is generated INSIDE
 * the AndroidKeyStore and can never be read back, the public half registered with the pool at
 * `/connect`, and every request signed. There is no secret to leak because there is no secret in the
 * app, and no identity to forge because the identity IS the key.
 */
public interface DeviceKey {

    /**
     * `{"kty":"EC","crv":"P-256","x":"…","y":"…"}` and nothing else.
     *
     * Exactly the four fields `worker/src/sponsored/app-devices.ts::normalizeDeviceJwk` keeps. It
     * drops `alg`, `use`, `key_ops` and `ext` rather than storing them, and REFUSES a JWK carrying a
     * private `d`, so sending the four is both the minimum and the whole of what is wanted.
     */
    public fun publicJwk(): String

    /** The raw 64-byte `r‖s` ECDSA P-256 signature over `message`. Never DER. See [Der]. */
    public fun signRaw(message: ByteArray): ByteArray

    /** Forget the key. The pool keeps the public half until the device row is revoked. */
    public fun delete()

    public companion object {
        /**
         * The JWK, as a string, from the two coordinates.
         *
         * Pure, so `DeviceKeyTest` pins the spelling without a Keystore: the field ORDER is the
         * worker's own (`kty, crv, x, y`) and there is no whitespace, because the value travels as a
         * base64url query parameter and every byte of it is paid for twice.
         */
        @JvmStatic
        public fun jwk(x: String, y: String): String =
            """{"kty":"EC","crv":"P-256","x":"$x","y":"$y"}"""

        /** The JWK of an EC public key. 32 bytes a coordinate, base64url, unpadded. */
        @JvmStatic
        public fun jwkOf(key: ECPublicKey): String {
            val point = key.w
            return jwk(
                x = B64.url(point.affineX.toFixedWidth(32)),
                y = B64.url(point.affineY.toFixedWidth(32)),
            )
        }

        /** What `device_key=` carries in the connect URL: the JWK, base64url, unpadded. */
        @JvmStatic
        public fun deviceKeyParam(jwk: String): String = B64.url(jwk.toByteArray(Charsets.UTF_8))
    }
}

/**
 * The AndroidKeyStore implementation. One key per app registration, aliased by the client id, so two
 * client ids in one process are two keys and neither can sign for the other.
 *
 * The key is generated on FIRST USE and then simply found again: a device that already holds one has
 * already been registered with the pool, and generating a second would strand the row we hold the
 * public half of. `delete()` is the only thing that ever removes it.
 */
public class AndroidKeystoreDeviceKey(
    private val alias: String,
) : DeviceKey {

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun publicJwk(): String {
        val entry = entry()
        val public = entry.certificate.publicKey
        require(public is ECPublicKey) { "the key at $alias is not an EC key" }
        return DeviceKey.jwkOf(public)
    }

    override fun signRaw(message: ByteArray): ByteArray {
        val signature = Signature.getInstance(ALGORITHM).apply {
            initSign(entry().privateKey)
            update(message)
        }
        // THE PLATFORM GIVES DER AND THE POOL TAKES RAW. See `Der` for why this line is the whole
        // difference between a signed call and a `401 bad_signature`.
        return Der.toRaw(signature.sign())
    }

    override fun delete() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    /** True when this device has a key already, which is the same question as "is it registered". */
    public fun exists(): Boolean = keyStore.containsAlias(alias)

    private fun entry(): KeyStore.PrivateKeyEntry {
        if (!keyStore.containsAlias(alias)) generate()
        val entry = keyStore.getEntry(alias, null)
        // A Keystore entry can survive a lock-screen change as something we cannot use. Rather than
        // signing with a key the pool never saw, say so: the fix is `delete()` and a fresh connect.
        return entry as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("The signing key at $alias is no longer usable. Connect again.")
    }

    private fun generate() {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // NO USER AUTHENTICATION. A signature is made on every call to the pool, including the
            // ones a background task makes, so a key that needs the screen unlocked would turn an
            // ordinary answer into a lock-screen prompt. The key is still non-exportable, which is
            // the property that matters: nothing can copy it off this device.
            .setUserAuthenticationRequired(false)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    public companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val CURVE = "secp256r1"
        private const val ALGORITHM = "SHA256withECDSA"

        /** The alias one client id's key lives at. Namespaced, so nothing else in the app collides. */
        @JvmStatic
        public fun aliasFor(clientId: String): String = "com.sponsoredtokens.device.$clientId"
    }
}
