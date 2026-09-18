package com.sponsoredtokens

import okhttp3.HttpUrl

/**
 * THE CANONICAL STRING IS A CONTRACT WITH THE WORKER, and this file is its Android copy.
 *
 *     METHOD \n PATH \n TIMESTAMP \n sha256(body)
 *
 * The verifier is `worker/src/sponsored/app-devices.ts::canonicalDeviceString`; the reference signer
 * is `sponsoredtokens-site/src/lib/device-key.ts`. This is a third spelling of the same four lines,
 * because that code cannot be imported here, and a copy that drifts is a library that cannot talk to
 * the pool. `CanonicalStringTest` pins every one of the four details below against fixed vectors.
 *
 *   · METHOD is UPPER CASE.
 *   · PATH is the pathname AND the query string, exactly as the worker builds it
 *     (`${url.pathname}${url.search}` in `worker/src/routes/sponsored-proxy.ts`), so a signature for
 *     `/api/v1/models?tier=0` cannot be lifted onto `?tier=3`. An EMPTY query is no `?` at all,
 *     because that is what `URL.search` answers for one.
 *   · TIMESTAMP is unix SECONDS as a decimal string, inside a FIVE MINUTE window.
 *   · `sha256(body)` is LOWERCASE HEX, and a request with no body carries the hash OF THE EMPTY
 *     STRING (`e3b0c442…`) rather than an empty field, so "no body" and "the field is missing" are
 *     different strings and neither can stand in for the other. A GET or a HEAD hashes the empty
 *     string whatever it is carrying, which is what the worker does before it verifies:
 *     `c.req.method === 'GET' || c.req.method === 'HEAD' ? '' : await c.req.raw.clone().text()`.
 *
 * And the signature header is base64 of the RAW 64-byte `r‖s` ECDSA P-256 signature, never DER. See
 * `Der`, which is the file that makes the platform's own output into that.
 */
public object Signing {

    /** The four header names, spelled as `worker/src/sponsored/config.ts` spells them. */
    public const val HEADER_APP: String = "X-Sponsoredtokens-App"
    public const val HEADER_DEVICE: String = "X-Sponsoredtokens-Device"
    public const val HEADER_TIMESTAMP: String = "X-Sponsoredtokens-Timestamp"
    public const val HEADER_SIGNATURE: String = "X-Sponsoredtokens-Signature"

    /** `DEVICE_SIGNATURE_WINDOW_SECONDS` in the worker's config. Here so a caller can say why. */
    public const val SIGNATURE_WINDOW_SECONDS: Long = 300

    /** `sha256("")` in lowercase hex. The hash a request with no body carries. */
    public const val EMPTY_BODY_SHA256: String =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** `METHOD\nPATH\nTIMESTAMP\nsha256(body)`, and nothing else, ever. */
    @JvmStatic
    public fun canonicalString(method: String, path: String, timestamp: String, bodyHashHex: String): String =
        "${method.uppercase()}\n$path\n$timestamp\n$bodyHashHex"

    /** `sha256(body)` in lowercase hex, over the bytes as they go on the wire. */
    @JvmStatic
    public fun bodyHash(body: ByteArray?): String =
        if (body == null || body.isEmpty()) EMPTY_BODY_SHA256 else sha256(body).toLowerHex()

    /**
     * The part of a URL a signature covers: the path and the query, and never the origin.
     *
     * `HttpUrl.encodedQuery` is null for `/x` and the EMPTY STRING for `/x?`, and the worker's
     * `URL.search` answers `""` for both - so an empty query contributes no `?` here either.
     */
    @JvmStatic
    public fun requestPath(url: HttpUrl): String {
        val query = url.encodedQuery
        return if (query.isNullOrEmpty()) url.encodedPath else "${url.encodedPath}?$query"
    }
}
