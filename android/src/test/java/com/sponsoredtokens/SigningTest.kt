package com.sponsoredtokens

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * THE CANONICAL STRING, PINNED.
 *
 * Every vector here is the worker's own rule restated as an assertion. If one of these ever fails,
 * the library has stopped being able to talk to the pool, and the failure names which of the four
 * details drifted. The verifier is `worker/src/sponsored/app-devices.ts::canonicalDeviceString` and
 * the reference signer is `sponsoredtokens-site/src/lib/device-key.ts`.
 */
class SigningTest {

    @Test
    fun `the four lines, in order, with the method upper cased`() {
        val canonical = Signing.canonicalString("post", "/api/v1/chat/completions", "1757340000", "abc123")
        assertEquals("POST\n/api/v1/chat/completions\n1757340000\nabc123", canonical)
    }

    @Test
    fun `an empty body is the sha256 of the empty string and never an empty field`() {
        // `e3b0c442…` is sha256(""), and the worker puts it in the fourth line rather than leaving
        // the line blank, so that "no body" and "the field is missing" are different strings.
        assertEquals(Signing.EMPTY_BODY_SHA256, Signing.bodyHash(null))
        assertEquals(Signing.EMPTY_BODY_SHA256, Signing.bodyHash(ByteArray(0)))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Signing.EMPTY_BODY_SHA256,
        )
    }

    @Test
    fun `the body hash is lowercase hex of sha256`() {
        // sha256("abc"), the standard vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Signing.bodyHash("abc".toByteArray()),
        )
    }

    @Test
    fun `a body is hashed as UTF-8`() {
        // sha256 of the three UTF-8 bytes of U+00E9, not of any other encoding of it.
        val hash = Signing.bodyHash("{\"a\":\"é\"}".toByteArray(Charsets.UTF_8))
        assertEquals(Signing.bodyHash("{\"a\":\"é\"}".toByteArray()), hash)
        assertNotEquals(Signing.bodyHash("{\"a\":\"e\"}".toByteArray()), hash)
    }

    @Test
    fun `the path carries the query, so a signature cannot be lifted between two of them`() {
        assertEquals("/api/v1/models?tier=0", Signing.requestPath("https://sponsoredtokens.com/api/v1/models?tier=0".toHttpUrl()))
        assertNotEquals(
            Signing.requestPath("https://sponsoredtokens.com/api/v1/models?tier=0".toHttpUrl()),
            Signing.requestPath("https://sponsoredtokens.com/api/v1/models?tier=3".toHttpUrl()),
        )
    }

    @Test
    fun `the path is the path alone, never the origin`() {
        assertEquals("/api/v1/models", Signing.requestPath("https://sponsoredtokens.com/api/v1/models".toHttpUrl()))
    }

    @Test
    fun `an empty query contributes no question mark, which is what URL search answers`() {
        // `new URL("https://x/a?").search` is "" in the worker, so this must be "/a" and not "/a?".
        assertEquals("/api/v1/models", Signing.requestPath("https://sponsoredtokens.com/api/v1/models?".toHttpUrl()))
    }

    @Test
    fun `a whole signed string for a fixed request`() {
        val url = "https://sponsoredtokens.com/api/v1/chat/completions".toHttpUrl()
        val body = """{"model":"sponsored/auto","messages":[]}"""
        val canonical = Signing.canonicalString("POST", Signing.requestPath(url), "1757340000", Signing.bodyHash(body.toByteArray()))
        assertEquals(
            "POST\n" +
                "/api/v1/chat/completions\n" +
                "1757340000\n" +
                Signing.bodyHash(body.toByteArray()),
            canonical,
        )
        // Four lines, which means three newlines and no trailing one.
        assertEquals(3, canonical.count { it == '\n' })
    }

    @Test
    fun `the four header names are the workers own`() {
        assertEquals("X-Sponsoredtokens-App", Signing.HEADER_APP)
        assertEquals("X-Sponsoredtokens-Device", Signing.HEADER_DEVICE)
        assertEquals("X-Sponsoredtokens-Timestamp", Signing.HEADER_TIMESTAMP)
        assertEquals("X-Sponsoredtokens-Signature", Signing.HEADER_SIGNATURE)
    }
}
