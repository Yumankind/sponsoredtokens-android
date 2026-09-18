package com.sponsoredtokens

/**
 * THE NATIVE CONNECT FLOW, as a pair of pure functions: the URL that goes out and the redirect that
 * comes back.
 *
 * ```
 *  1. app  → a Custom Tab on
 *            GET <site>/connect
 *                ?client_id=…&redirect_uri=…&state=…
 *                &code_challenge=…&code_challenge_method=S256
 *                &device_key=<base64url of {"kty":"EC","crv":"P-256","x":…,"y":…}>
 *                &device_name=<1..64 characters>
 *  2. person → signs in on OUR page, passes Turnstile there, presses Connect
 *  3. browser → <redirect_uri>?code=…&state=…
 *  4. app   → POST <site>/api/people/connect/token { code, code_verifier, client_id, redirect_uri }
 *           ← { personToken, expiresAt, person, allowance, signingDevice: { id, appId } }
 * ```
 *
 * `redirect_uri` is matched by the worker AS A WHOLE STRING and never as a prefix, so the value the
 * app registers, the value it sends here and the value it sends at the exchange must be one string.
 * `SponsoredTokens` holds exactly one and uses it in all three places for that reason.
 *
 * The top-up flow (`HANDOFF-people.md` §5c) is the same three parameters and NO PKCE: it ends in a
 * payment into the person's own account rather than in a credential, so there is nothing to bind.
 */
public object Connect {

    public const val CONNECT_PATH: String = "/connect"
    public const val TOPUP_PATH: String = "/topup"
    public const val TOKEN_PATH: String = "/api/people/connect/token"

    /** `device_name` is 1 to 64 characters. Longer is cut here rather than refused at the door. */
    public const val DEVICE_NAME_MAX: Int = 64

    /**
     * The URL the Custom Tab opens.
     *
     * `deviceKeyJwk` is the JWK ITSELF, not the base64url: the encoding is this function's business,
     * so a caller cannot half-do it.
     */
    @JvmStatic
    public fun connectUrl(
        site: String,
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        deviceKeyJwk: String?,
        deviceName: String?,
    ): String = Uris.build(
        "${site.trimEnd('/')}$CONNECT_PATH",
        linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to PkcePair.METHOD,
            "device_key" to deviceKeyJwk?.let { DeviceKey.deviceKeyParam(it) },
            "device_name" to deviceName?.let { sanitizeDeviceName(it) },
        ),
    )

    /** `<site>/topup?client_id=&redirect_uri=&state=`. Three parameters, and no challenge. */
    @JvmStatic
    public fun topUpUrl(site: String, clientId: String, redirectUri: String, state: String): String = Uris.build(
        "${site.trimEnd('/')}$TOPUP_PATH",
        linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "state" to state,
        ),
    )

    /**
     * A device name the door will take: trimmed, one line, and never more than 64 characters.
     *
     * Control characters are dropped rather than encoded. The value is shown to the person on their
     * own account page beside every other device they have connected, so a newline in it is a line
     * in somebody's list that should not be there.
     */
    @JvmStatic
    public fun sanitizeDeviceName(raw: String): String {
        val cleaned = raw.map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return "Android"
        return if (cleaned.length <= DEVICE_NAME_MAX) cleaned else cleaned.substring(0, DEVICE_NAME_MAX).trim()
    }

    /**
     * What came back on the redirect.
     *
     * [Ignored] rather than an exception for anything that is not ours: an app hands this every
     * `Intent` it receives, and most of them are somebody else's deep link.
     */
    public sealed class Redirect {
        /** The connect flow finished. The code is single-use and expires in minutes. */
        public data class Code(val code: String, val state: String?) : Redirect()

        /** The top-up flow finished. `paid` is `topup=done`, which is Stripe's own success URL. */
        public data class TopUp(val paid: Boolean, val state: String?) : Redirect()

        /** The page said no, in OAuth's own vocabulary (`error`, `error_description`). */
        public data class Refused(val error: String, val description: String?, val state: String?) : Redirect()

        /** Not a sponsoredtokens redirect at all. */
        public object Ignored : Redirect()
    }

    /**
     * Read a redirect URI.
     *
     * `redirectUri` is the one the app registered; a URI that does not START WITH it is somebody
     * else's, and is [Redirect.Ignored] before any parameter is looked at. The comparison is on the
     * part before the query, as a whole string, which is the worker's own rule for the same value.
     *
     * NOTE THAT THE STATE IS NOT CHECKED HERE. This function reads; `SponsoredTokens.handleRedirect`
     * is what compares the state against the one it stored and refuses a mismatch, because only the
     * thing holding the pending flow knows what was sent.
     */
    @JvmStatic
    public fun parseRedirect(uri: String, redirectUri: String): Redirect {
        val head = uri.substringBefore('?').substringBefore('#')
        if (head.trimEnd('/') != redirectUri.substringBefore('?').substringBefore('#').trimEnd('/')) return Redirect.Ignored
        val params = Uris.query(uri)
        val state = params["state"]
        params["error"]?.let { return Redirect.Refused(it, params["error_description"], state) }
        params["code"]?.takeIf { it.isNotEmpty() }?.let { return Redirect.Code(it, state) }
        when (params["topup"]) {
            "done" -> return Redirect.TopUp(paid = true, state = state)
            "cancelled" -> return Redirect.TopUp(paid = false, state = state)
        }
        return Redirect.Ignored
    }
}
