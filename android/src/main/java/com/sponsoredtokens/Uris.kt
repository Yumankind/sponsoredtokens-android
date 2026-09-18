package com.sponsoredtokens

import java.io.ByteArrayOutputStream

/**
 * PERCENT ENCODING, done here rather than borrowed.
 *
 * `android.net.Uri` would work on a phone and throw in a JVM unit test, and `java.net.URLEncoder`
 * spells a space as `+` and a tilde as `%7E`, neither of which is what `encodeURIComponent` does on
 * the other side of this contract. So the two directions are written out: they are twenty lines,
 * they are pure, and they are the difference between a `state` that comes back the way it went and
 * one that quietly gains a plus sign.
 */
internal object Uris {

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    /** `encodeURIComponent`, to the letter, including the six punctuation marks it leaves alone. */
    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xff).toChar()
            if (UNRESERVED.indexOf(ch) >= 0) {
                out.append(ch)
            } else {
                out.append('%')
                out.append(HEX_UPPER[(byte.toInt() and 0xff) ushr 4])
                out.append(HEX_UPPER[byte.toInt() and 0x0f])
            }
        }
        return out.toString()
    }

    /**
     * `decodeURIComponent`, to the letter - WHICH MEANS `+` IS A PLUS SIGN AND NOT A SPACE.
     *
     * Form encoding says otherwise, and this is deliberately not form encoding: every value this
     * library decodes (a PKCE `state`, an authorization `code`, a sponsor's name off a response
     * header) is written by `encodeURIComponent` or by the worker's `asciiHeaderValue`, which is the
     * same rule. Turning a `+` into a space would corrupt a base64 value that legitimately has one.
     *
     * A malformed escape is left as it stands rather than throwing: this decodes third-party input,
     * and a sponsor's display name is not worth an exception in the middle of an answer.
     */
    fun decode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val hi = hex(value[i + 1])
                val lo = hex(value[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * The query of a URI as a map, whatever the scheme.
     *
     * Written by hand because a redirect is a CUSTOM SCHEME - `com.example.app:/oauth2redirect?…`
     * and `myapp://auth/callback?…` are both legal and `java.net.URI` treats the first as opaque, so
     * its own `query` is null for it. What is wanted is simply everything after the first `?` and
     * before any `#`, which is the same for every scheme there is.
     */
    fun query(uri: String): Map<String, String> {
        val start = uri.indexOf('?')
        if (start < 0) return emptyMap()
        val hash = uri.indexOf('#', start)
        val raw = if (hash < 0) uri.substring(start + 1) else uri.substring(start + 1, hash)
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            // FIRST WINS. A redirect carrying `?code=a&code=b` is somebody trying to make two readers
            // of the same URL disagree, and the first is what a browser's own `URLSearchParams.get`
            // answers.
            out.putIfAbsent(Uris.decode(key), Uris.decode(value))
        }
        return out
    }

    /** `<base>?a=1&b=2`, with every value encoded. Empty and null values are left out entirely. */
    fun build(base: String, params: Map<String, String?>): String {
        val query = params.entries
            .filter { !it.value.isNullOrEmpty() }
            .joinToString("&") { "${encode(it.key)}=${encode(it.value!!)}" }
        if (query.isEmpty()) return base
        return if (base.contains('?')) "$base&$query" else "$base?$query"
    }

    private fun hex(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    private const val HEX_UPPER = "0123456789ABCDEF"
}
