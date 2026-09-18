package com.sponsoredtokens

/**
 * DER IN, RAW `r‖s` OUT - the one conversion that stands between Android and the pool.
 *
 * `java.security.Signature("SHA256withECDSA")`, which is what the AndroidKeyStore gives, emits an
 * X9.62 DER SEQUENCE of two INTEGERs. WebCrypto's `subtle.sign`, which is what the worker was
 * written against, emits the raw 64-byte `r‖s` form, and the verifier
 * (`worker/src/sponsored/app-devices.ts::decodeSignature`) REFUSES ANYTHING ELSE BY LENGTH:
 *
 *     return out.length === 64 ? out : null;
 *
 * So a DER signature sent as it comes out of the platform is a `401 bad_signature` every time, and
 * this file is why that does not happen. It is pure, it touches nothing Android, and
 * `DerTest` runs it against signatures a real JVM EC key produced.
 */
internal object Der {

    private const val COORDINATE = 32
    private const val RAW = COORDINATE * 2

    /** An X9.62 DER `SEQUENCE { INTEGER r, INTEGER s }` as the 64 bytes the verifier wants. */
    fun toRaw(der: ByteArray): ByteArray {
        var i = 0
        require(der.size >= 8) { "an ECDSA signature is never this short" }
        require(der[i++].toInt() and 0xff == 0x30) { "not a DER SEQUENCE" }
        val seqLen = readLength(der, i).also { i = it.next }
        require(seqLen.value == der.size - i) { "the SEQUENCE length does not match the signature" }

        val r = readInteger(der, i).also { i = it.next }
        val s = readInteger(der, i).also { i = it.next }
        require(i == der.size) { "trailing bytes after the two INTEGERs" }

        val out = ByteArray(RAW)
        copyRight(r.value, out, 0)
        copyRight(s.value, out, COORDINATE)
        return out
    }

    /**
     * The inverse. Nothing in the library sends a DER signature, but a test that cannot hand a raw
     * signature back to `Signature.verify` cannot prove the conversion kept the mathematics.
     */
    fun fromRaw(raw: ByteArray): ByteArray {
        require(raw.size == RAW) { "a raw P-256 signature is $RAW bytes, not ${raw.size}" }
        val r = derInteger(raw.copyOfRange(0, COORDINATE))
        val s = derInteger(raw.copyOfRange(COORDINATE, RAW))
        val body = r + s
        return byteArrayOf(0x30) + derLength(body.size) + body
    }

    private class Read(val value: Int, val next: Int)
    private class Slice(val value: ByteArray, val next: Int)

    private fun readLength(der: ByteArray, at: Int): Read {
        var i = at
        val first = der[i++].toInt() and 0xff
        if (first < 0x80) return Read(first, i)
        val count = first and 0x7f
        require(count in 1..4) { "a length this library will not read" }
        var value = 0
        repeat(count) {
            value = (value shl 8) or (der[i++].toInt() and 0xff)
        }
        require(value >= 0) { "a negative length" }
        return Read(value, i)
    }

    private fun readInteger(der: ByteArray, at: Int): Slice {
        var i = at
        require(der[i++].toInt() and 0xff == 0x02) { "not a DER INTEGER" }
        val len = readLength(der, i).also { i = it.next }
        require(len.value > 0 && i + len.value <= der.size) { "an INTEGER that runs off the end" }
        val bytes = der.copyOfRange(i, i + len.value)
        return Slice(bytes, i + len.value)
    }

    /**
     * Right-align an INTEGER's bytes into its 32-byte slot.
     *
     * A DER INTEGER is signed and minimally encoded, so `r` arrives with a leading `0x00` whenever
     * its top bit is set, and shorter than 32 bytes whenever it happens to be small. Both are
     * ordinary and both are the reason this is not a copy.
     */
    private fun copyRight(value: ByteArray, out: ByteArray, offset: Int) {
        var from = 0
        while (from < value.size - 1 && value[from].toInt() == 0) from++
        val len = value.size - from
        require(len <= COORDINATE) { "an ECDSA component wider than $COORDINATE bytes" }
        System.arraycopy(value, from, out, offset + (COORDINATE - len), len)
    }

    private fun derInteger(component: ByteArray): ByteArray {
        var from = 0
        while (from < component.size - 1 && component[from].toInt() == 0) from++
        var body = component.copyOfRange(from, component.size)
        // A DER INTEGER is SIGNED: a top bit that is set would read as a negative number, so the
        // encoder puts a zero byte in front of it.
        if (body[0].toInt() and 0x80 != 0) body = byteArrayOf(0) + body
        return byteArrayOf(0x02) + derLength(body.size) + body
    }

    private fun derLength(length: Int): ByteArray =
        if (length < 0x80) byteArrayOf(length.toByte()) else byteArrayOf(0x81.toByte(), length.toByte())
}
