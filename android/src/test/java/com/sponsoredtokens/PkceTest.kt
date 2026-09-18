package com.sponsoredtokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** PKCE is the WHOLE of the authentication at the token door, so its shape is a contract. */
class PkceTest {

    @Test
    fun `a pair is two 43-character base64url strings`() {
        val pair = PkcePair.generate()
        assertEquals(43, pair.verifier.length)
        assertEquals(43, pair.challenge.length)
        // RFC 7636's unreserved set, and no padding: a `=` in a query parameter is a percent escape
        // in every reader and a source of mismatched verifiers in half of them.
        assertTrue(pair.verifier.matches(Regex("^[A-Za-z0-9_-]{43}$")))
        assertTrue(pair.challenge.matches(Regex("^[A-Za-z0-9_-]{43}$")))
        assertEquals("S256", pair.method)
    }

    @Test
    fun `the challenge is base64url of sha256 of the verifier, computed independently`() {
        val pair = PkcePair.generate()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(pair.verifier.toByteArray(Charsets.US_ASCII)))
        assertEquals(expected, pair.challenge)
    }

    @Test
    fun `the RFC's own worked example`() {
        // RFC 7636 appendix B: this verifier hashes to this challenge, and any implementation that
        // disagrees with it disagrees with every server there is.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkcePair.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `two pairs are two pairs`() {
        assertNotEquals(PkcePair.generate().verifier, PkcePair.generate().verifier)
        assertNotEquals(PkcePair.newState(), PkcePair.newState())
    }
}
