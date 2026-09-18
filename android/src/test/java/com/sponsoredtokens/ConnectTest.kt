package com.sponsoredtokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The URL that goes out and the redirect that comes back, both of them whole strings that matter. */
class ConnectTest {

    private val jwk = DeviceKey.jwk(x = "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU", y = "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0")

    @Test
    fun `the connect URL carries every parameter the contract names`() {
        val url = Connect.connectUrl(
            site = "https://sponsoredtokens.com",
            clientId = "app_yours",
            redirectUri = "com.example.app:/oauth2redirect",
            state = "st4te",
            codeChallenge = "chall3nge",
            deviceKeyJwk = jwk,
            deviceName = "Pixel 9",
        )
        val q = Uris.query(url)
        assertTrue(url.startsWith("https://sponsoredtokens.com/connect?"))
        assertEquals("app_yours", q["client_id"])
        assertEquals("com.example.app:/oauth2redirect", q["redirect_uri"])
        assertEquals("st4te", q["state"])
        assertEquals("chall3nge", q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("Pixel 9", q["device_name"])
        // `device_key` is the JWK itself, base64url, and it decodes back to the four fields the
        // worker's `normalizeDeviceJwk` keeps - in that order and with nothing else in it.
        assertEquals(jwk, String(B64.urlDecode(q["device_key"]!!), Charsets.UTF_8))
        assertTrue(jwk.startsWith("""{"kty":"EC","crv":"P-256","x":"""))
    }

    @Test
    fun `the redirect URI is percent encoded, colon and slash included`() {
        val url = Connect.connectUrl(
            "https://sponsoredtokens.com", "app_yours", "com.example.app:/oauth2redirect",
            "s", "c", null, null,
        )
        assertTrue(url.contains("redirect_uri=com.example.app%3A%2Foauth2redirect"))
        // Absent rather than empty: a `device_key=` with nothing after it is a parameter the door
        // has to have an opinion about, and there is nothing to say.
        assertTrue(!url.contains("device_key"))
        assertTrue(!url.contains("device_name"))
    }

    @Test
    fun `a trailing slash on the site does not double`() {
        assertTrue(
            Connect.connectUrl("https://sponsoredtokens.com/", "a", "b", "c", "d", null, null)
                .startsWith("https://sponsoredtokens.com/connect?"),
        )
    }

    @Test
    fun `the top-up URL is three parameters and no challenge`() {
        val url = Connect.topUpUrl("https://sponsoredtokens.com", "app_yours", "myapp://auth/callback", "st")
        val q = Uris.query(url)
        assertTrue(url.startsWith("https://sponsoredtokens.com/topup?"))
        assertEquals(setOf("client_id", "redirect_uri", "state"), q.keys)
    }

    @Test
    fun `a device name is one line and never more than 64 characters`() {
        assertEquals("Pixel 9", Connect.sanitizeDeviceName("  Pixel   9\n"))
        assertEquals(64, Connect.sanitizeDeviceName("x".repeat(200)).length)
        assertEquals("Android", Connect.sanitizeDeviceName("   "))
        assertTrue(!Connect.sanitizeDeviceName("a\nb").contains('\n'))
    }

    @Test
    fun `a code redirect is read on both custom scheme shapes`() {
        val a = Connect.parseRedirect("com.example.app:/oauth2redirect?code=abc&state=xyz", "com.example.app:/oauth2redirect")
        assertEquals(Connect.Redirect.Code("abc", "xyz"), a)
        val b = Connect.parseRedirect("myapp://auth/callback?code=abc&state=xyz", "myapp://auth/callback")
        assertEquals(Connect.Redirect.Code("abc", "xyz"), b)
    }

    @Test
    fun `a redirect for somebody else is ignored rather than refused`() {
        // An app hands this every Intent it receives, and most of them are another feature's.
        assertEquals(
            Connect.Redirect.Ignored,
            Connect.parseRedirect("myapp://other/thing?code=abc", "myapp://auth/callback"),
        )
    }

    @Test
    fun `the two top-up outcomes`() {
        assertEquals(
            Connect.Redirect.TopUp(paid = true, state = "s"),
            Connect.parseRedirect("myapp://auth/callback?topup=done&state=s", "myapp://auth/callback"),
        )
        assertEquals(
            Connect.Redirect.TopUp(paid = false, state = "s"),
            Connect.parseRedirect("myapp://auth/callback?topup=cancelled&state=s", "myapp://auth/callback"),
        )
    }

    @Test
    fun `an error redirect keeps OAuth's own vocabulary`() {
        val refused = Connect.parseRedirect(
            "myapp://auth/callback?error=access_denied&error_description=The%20person%20said%20no&state=s",
            "myapp://auth/callback",
        )
        assertEquals(Connect.Redirect.Refused("access_denied", "The person said no", "s"), refused)
    }

    @Test
    fun `a redirect with nothing on it is ignored`() {
        assertEquals(Connect.Redirect.Ignored, Connect.parseRedirect("myapp://auth/callback", "myapp://auth/callback"))
    }

    @Test
    fun `a fragment is not part of the query`() {
        val parsed = Connect.parseRedirect("myapp://auth/callback?code=abc#nothing", "myapp://auth/callback")
        assertEquals(Connect.Redirect.Code("abc", null), parsed)
    }

    @Test
    fun `the first value of a repeated parameter wins`() {
        // Two `code` values is somebody trying to make two readers of one URL disagree.
        assertEquals("a", Uris.query("x://y?code=a&code=b")["code"])
    }

    @Test
    fun `decoding is decodeURIComponent and a plus stays a plus`() {
        // Every value here is written by `encodeURIComponent` or by the worker's own encoder, which
        // is the same rule. Form decoding would corrupt a base64 value with a plus sign in it.
        assertEquals("a+b", Uris.decode("a+b"))
        assertEquals("a b", Uris.decode("a%20b"))
        assertEquals("100%", Uris.decode("100%25"))
        assertEquals("é", Uris.decode("%C3%A9"))
        assertNull(Uris.query("x://y")["anything"])
    }

    @Test
    fun `a malformed escape is left alone rather than thrown`() {
        assertEquals("100%zz", Uris.decode("100%zz"))
    }
}
