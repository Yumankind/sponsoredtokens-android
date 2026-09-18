package com.sponsoredtokens

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * BASE64URL WITHOUT PADDING, and the two other encodings this library has to agree with the worker
 * about.
 *
 * `java.util.Base64` rather than `android.util.Base64` on purpose: the first is plain Java, so every
 * function in this file runs unchanged in a JVM unit test, and the second would make the canonical
 * string untestable without a device. It is available from API 26, which is this library's minSdk.
 */
internal object B64 {

    private val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()
    private val STD_ENCODER: Base64.Encoder = Base64.getEncoder()

    /** base64url, no `=`. What a JWK coordinate, a PKCE verifier and a challenge are spelled in. */
    fun url(bytes: ByteArray): String = URL_ENCODER.encodeToString(bytes)

    /** Accepts padded and unpadded input, and the standard alphabet as well as the url one. */
    fun urlDecode(text: String): ByteArray = URL_DECODER.decode(text.trimEnd('='))

    /**
     * STANDARD base64, WITH padding, which is what `X-Sponsoredtokens-Signature` carries.
     *
     * The verifier (`worker/src/sponsored/app-devices.ts::decodeSignature`) accepts either alphabet
     * and re-pads what it gets, so this could be either; it is the standard one because that is what
     * `btoa` produces in the browser helper this signer is a twin of, and two twins that spell the
     * same bytes two ways are a difference somebody has to check.
     */
    fun standard(bytes: ByteArray): String = STD_ENCODER.encodeToString(bytes)
}

/** Lowercase hex, which is the spelling the canonical string's body hash is in and no other. */
internal fun ByteArray.toLowerHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4])
        out.append(HEX[v and 0x0f])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"

/** `sha256(bytes)`. One place, so the body hash and the PKCE challenge cannot drift apart. */
internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/**
 * A P-256 coordinate as the 32 bytes it is, whatever `BigInteger` says about its length.
 *
 * `BigInteger.toByteArray()` is two's complement, so it gives 33 bytes with a leading zero for any
 * coordinate whose top bit is set, and fewer than 32 for a small one. A JWK coordinate is a FIXED
 * WIDTH field: the worker's `normalizeDeviceJwk` refuses anything that is not 42 to 44 base64url
 * characters, and an unpadded 31-byte coordinate is 42 characters of the wrong number.
 */
internal fun BigInteger.toFixedWidth(width: Int): ByteArray {
    val raw = toByteArray()
    if (raw.size == width) return raw
    require(signum() >= 0) { "a coordinate is never negative" }
    val out = ByteArray(width)
    if (raw.size < width) {
        System.arraycopy(raw, 0, out, width - raw.size, raw.size)
        return out
    }
    // Longer than the width: the excess is the two's-complement sign byte, and nothing else.
    val excess = raw.size - width
    for (i in 0 until excess) require(raw[i].toInt() == 0) { "a coordinate wider than $width bytes" }
    System.arraycopy(raw, excess, out, 0, width)
    return out
}
