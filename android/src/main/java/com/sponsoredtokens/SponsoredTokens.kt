package com.sponsoredtokens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Which credential a call to `/api/v1/...` carries. */
public enum class AuthMode {
    /**
     * Signed when the connect flow gave us a device, the bearer otherwise. The default.
     *
     * A signature needs NO STORED SECRET: the private half is in the AndroidKeyStore and cannot be
     * read by this app, by a backup, or by anything that gets the data directory. A bearer is a
     * string that has to be kept somewhere. So the road with nothing to steal is the one taken
     * whenever it exists.
     */
    AUTO,

    /** Always the four signing headers. Fails loudly when there is no device, rather than quietly. */
    SIGNED,

    /** Always the person token. Simplest, and 30 days long. */
    BEARER,
}

/**
 * THE POOL, FROM AN ANDROID APP.
 *
 * ```kotlin
 * val sponsored = SponsoredTokens(
 *     context = this,
 *     clientId = "app_yours",
 *     redirectUri = "com.example.app:/oauth2redirect",
 * )
 *
 * sponsored.signIn(activity)                      // a Custom Tab on our page
 * // …and in the activity that receives the redirect:
 * when (val result = sponsored.handleRedirect(intent)) {
 *     is ConnectResult.Connected -> chat(result.session)
 *     else -> Unit
 * }
 *
 * val client = sponsored.client                   // an OkHttpClient that signs
 * val model  = sponsored.defaultModel()           // "sponsored/auto" where the app has a policy
 * ```
 *
 * ── WHAT IT HOLDS ───────────────────────────────────────────────────────────────────────────────
 *
 * One person token, one Keystore keypair, and the flow that is in the middle of happening. Nothing
 * else: the pool's own rules about allowances, tiers, models and purses are the POOL'S, and a client
 * that cached a copy of them would be a second place for them to be wrong. Every figure this class
 * reports came off an answer, in this turn.
 *
 * ── THE TWO ROADS ───────────────────────────────────────────────────────────────────────────────
 *
 * `/api/v1/...` - the money - goes by [authMode]. `/api/people/...` - who you are, what you have left,
 * signing out - always goes by the person token, because those doors are the PERSON'S and a
 * signature identifies the app's device. That split is deliberate and is the only rule about which
 * credential goes where.
 */
public class SponsoredTokens @JvmOverloads constructor(
    context: Context,
    public val clientId: String,
    public val redirectUri: String,
    public val site: String = DEFAULT_SITE,
    public val store: TokenStore = KeystoreTokenStore(context.applicationContext, clientId),
    httpClient: OkHttpClient = OkHttpClient(),
    public val authMode: AuthMode = AuthMode.AUTO,
    private val deviceKey: DeviceKey = AndroidKeystoreDeviceKey(AndroidKeystoreDeviceKey.aliasFor(clientId)),
    private val deviceName: String = defaultDeviceName(),
    /** Unix SECONDS. Injected so a test can pin a signature rather than the clock. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    private val app: Context = context.applicationContext
    private val siteUrl: HttpUrl = site.trimEnd('/').toHttpUrl()

    /** The pending flow: the PKCE verifier and the state, encrypted exactly as the token is. */
    private val pending: TokenStore = KeystoreTokenStore(app, "$clientId.pending")

    private val _session = MutableStateFlow(Session.read(store.read()))
    private val _lastAnswer = MutableStateFlow<SponsoredAnswer?>(null)

    /** The connected session, or null. Collect it to draw a sign-in button that disappears. */
    public val sessionFlow: StateFlow<Session?> = _session.asStateFlow()

    /**
     * WHAT THE LAST ANSWER SAID ABOUT THE MONEY, and who paid for it.
     *
     * Updated by the interceptor on every answer that carried any `x-sponsored-*` header, including
     * a refusal - a client that has just been told its week is spent is the one that most needs to
     * know when the week comes back.
     */
    public val lastAnswer: StateFlow<SponsoredAnswer?> = _lastAnswer.asStateFlow()

    /** Impressions and the rotation feed. See [Impressions]. */
    public val impressions: Impressions = Impressions(
        post = { path, body -> postSigned(path, body) },
        get = { path -> getPublic(path) },
    )

    /**
     * An `OkHttpClient` that signs, reads the money headers, and is otherwise the one you passed.
     *
     * Built with `newBuilder`, so the app's own timeouts, cache, DNS, proxy and interceptors are all
     * still there and the connection pool is SHARED rather than doubled.
     */
    public val client: OkHttpClient = httpClient.newBuilder()
        .addInterceptor(
            SponsoredInterceptor(
                appliesTo = ::isPool,
                headersFor = { method, url, body -> credentialHeaders(method, url, body) },
                onAnswer = { _lastAnswer.value = it },
            ),
        )
        .build()

    /** The base URL an OpenAI-compatible client is pointed at. */
    public val apiBaseUrl: String get() = "${site.trimEnd('/')}/api/v1"

    public val session: Session? get() = _session.value

    public val isConnected: Boolean get() = _session.value != null

    // ── Connecting ──────────────────────────────────────────────────────────────────────────────

    /**
     * Open our page in a Chrome Custom Tab and let the person sign in.
     *
     * A CUSTOM TAB AND NOT A WEBVIEW, for three reasons that are all the same reason: the person can
     * see the address bar and the padlock, so they know whose page they are typing a password into;
     * their existing session and passkeys are there, so most of them press one button; and Turnstile
     * runs in a real browser rather than in an embedded one that looks like automation. A phone with
     * no Custom Tabs provider falls back to whatever browser it does have.
     *
     * The keypair is generated here, on the first call, and the PUBLIC half travels in the URL. The
     * private half never leaves the Keystore and could not: it is not exportable.
     */
    public fun signIn(activity: Activity) {
        val pkce = PkcePair.generate()
        val state = PkcePair.newState()
        pending.write(JSONObject().put("verifier", pkce.verifier).put("state", state).toString())
        val jwk = runCatching { deviceKey.publicJwk() }.getOrNull()
        open(
            activity,
            Connect.connectUrl(
                site = site,
                clientId = clientId,
                redirectUri = redirectUri,
                state = state,
                codeChallenge = pkce.challenge,
                deviceKeyJwk = jwk,
                deviceName = deviceName,
            ),
        )
    }

    /** What a redirect turned out to be. */
    public sealed class ConnectResult {
        /** The person connected. The session is stored before this is returned. */
        public data class Connected(public val session: Session) : ConnectResult()

        /** A top-up came back. `paid` is Stripe's own success URL and NOT proof the balance moved. */
        public data class ToppedUp(public val paid: Boolean) : ConnectResult()

        /** The page refused, in OAuth's vocabulary. */
        public data class Refused(public val error: String, public val description: String?) : ConnectResult()

        /** Not ours. Hand this every redirect the app receives; most of them are somebody else's. */
        public object Ignored : ConnectResult()
    }

    /** The `Intent` an activity was launched or resumed with. `data` is the redirect. */
    public suspend fun handleRedirect(intent: Intent): ConnectResult {
        val data = intent.data?.toString() ?: return ConnectResult.Ignored
        return handleRedirect(data)
    }

    public suspend fun handleRedirect(uri: Uri): ConnectResult = handleRedirect(uri.toString())

    /**
     * Read a redirect, and where it carries a code, exchange it.
     *
     * THE STATE IS CHECKED HERE and nowhere else, against the one [signIn] stored: a redirect
     * carrying somebody else's code is how an attacker gets an app to connect an account that is not
     * the person's. A mismatch is [ConnectResult.Refused] with `invalid_state` and the pending flow
     * is thrown away, because a flow that has been answered wrongly once is finished.
     */
    public suspend fun handleRedirect(uri: String): ConnectResult {
        return when (val redirect = Connect.parseRedirect(uri, redirectUri)) {
            is Connect.Redirect.Ignored -> ConnectResult.Ignored
            is Connect.Redirect.Refused -> {
                pending.clear()
                ConnectResult.Refused(redirect.error, redirect.description)
            }
            // A TOP-UP CARRIES NO PKCE AND CLEARS NOTHING. `/topup` ends in a payment into the
            // person's own account and hands the app nothing, so there is no pending flow of its own
            // and a connect flow that happens to be open must survive it.
            is Connect.Redirect.TopUp -> ConnectResult.ToppedUp(redirect.paid)
            is Connect.Redirect.Code -> {
                val saved = runCatching { JSONObject(pending.read() ?: "") }.getOrNull()
                val verifier = saved?.optStringOrNull("verifier")
                val state = saved?.optStringOrNull("state")
                if (verifier == null) return ConnectResult.Refused("invalid_state", "No connect flow is in progress.")
                if (state != null && state != redirect.state) {
                    pending.clear()
                    return ConnectResult.Refused("invalid_state", "This redirect does not belong to the flow this app started.")
                }
                val session = exchange(redirect.code, verifier)
                pending.clear()
                ConnectResult.Connected(session)
            }
        }
    }

    /**
     * `POST /api/people/connect/token`. No credential: the verifier IS the authentication.
     *
     * Every refusal of this door reads the same from outside - unknown code, replayed code, expired
     * code, wrong redirect, wrong client and wrong verifier are all `400 invalid_grant` - so a
     * caller who is guessing learns nothing, and this one does not try to tell them apart either.
     */
    private suspend fun exchange(code: String, verifier: String): Session {
        val body = JSONObject().apply {
            put("code", code)
            put("code_verifier", verifier)
            put("client_id", clientId)
            put("redirect_uri", redirectUri)
        }
        val json = postJson(Connect.TOKEN_PATH, body, bearer = null)
        val session = Session.of(json)
            ?: throw SponsoredException(200, "invalid_grant", "The connect exchange answered without a person token.")
        store.write(session.toJson())
        _session.value = session
        return session
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    /**
     * `GET /api/people/me` - the person, the allowance, the pool, the app and the last sponsor.
     *
     * The point of this door is that NO CLIENT HAS TO PARSE A FOOTER. The footer is still appended to
     * every answer the pool pays for and nothing changes it, but an app that wants to draw
     * "sponsored by Acme, $3.63 left this week" in its own chrome reads it here.
     */
    public suspend fun me(): Me = Me.of(getJson("/api/people/me", bearer = requireToken()))

    /**
     * `GET /api/v1/models` - the shelf THIS CREDENTIAL may spend, already prefixed.
     *
     * Sent with the same credential the spending calls use, deliberately: the listing is a promise
     * about what the caller can spend, and a list fetched with the bearer while the turns go out
     * signed would be a promise about somebody else. A client that picks from this list never meets
     * a model refusal.
     */
    public suspend fun models(): ModelShelf = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(siteUrl.newBuilder().encodedPath("/api/v1/models").build()).get().build()
        ModelShelf.of(JSONObject(execute(client, request)))
    }

    /**
     * What to send when the app has no opinion: `sponsored/auto` where the app has a model policy,
     * and otherwise the first row of the shelf, which is the dearest model this tier unlocks.
     */
    public suspend fun defaultModel(): String =
        models().defaultModel() ?: throw SponsoredException(200, "no_models", "The pool listed no model this credential can spend.")

    // ── Leaving ─────────────────────────────────────────────────────────────────────────────────

    /**
     * `DELETE /api/people/token`, then forget everything on this phone.
     *
     * The store is cleared EVEN WHEN THE CALL FAILS. A person who pressed sign out on a plane has
     * signed out; the token dies of its own accord in at most thirty days, and the alternative is an
     * app that still holds a credential after saying it does not.
     */
    public suspend fun signOut() {
        val token = _session.value?.personToken
        try {
            if (token != null) {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(siteUrl.newBuilder().encodedPath("/api/people/token").build())
                        .header("Authorization", "Bearer $token")
                        .delete()
                        .build()
                    execute(bare, request)
                }
            }
        } catch (_: Exception) {
            // Deliberately swallowed. See this function's own note.
        } finally {
            store.clear()
            pending.clear()
            _session.value = null
            _lastAnswer.value = null
        }
    }

    /**
     * Forget the SIGNING KEY as well as the token.
     *
     * Separate from [signOut] because the two are different acts: signing out ends a session, and
     * this ends an identity. The device row on the person's account page survives either until they
     * revoke it there, so a key deleted here can never be used again by anyone, including us.
     */
    public suspend fun forgetDevice() {
        signOut()
        runCatching { deviceKey.delete() }
    }

    // ── Topping up ──────────────────────────────────────────────────────────────────────────────

    /**
     * `<site>/topup?client_id=&redirect_uri=&state=` (§5c). Three parameters and no PKCE.
     *
     * Worth opening only where `me().paid?.allowed` is true, which is the app's own
     * `people_pay_own_tokens` switch: elsewhere the person's balance is not reachable by this
     * credential and filling it would buy them nothing here.
     */
    @JvmOverloads
    public fun topUpUrl(state: String = PkcePair.newState()): String =
        Connect.topUpUrl(site, clientId, redirectUri, state)

    /**
     * Open it.
     *
     * `topup=done` MEANS THE PAYMENT WAS TAKEN AND NOT THAT THE BALANCE HAS MOVED: the credit lands
     * by Stripe webhook, seconds later and out of band. So re-read [me] on return and again a few
     * seconds later until `paid.balanceCents` moves, rather than drawing a figure from the redirect.
     */
    @JvmOverloads
    public fun startTopUp(activity: Activity, state: String = PkcePair.newState()) {
        open(activity, topUpUrl(state))
    }

    // ── The credential, for anything that is not our own client ─────────────────────────────────

    /**
     * The headers one request to the pool carries, for a library that cannot take an interceptor.
     *
     * A SIGNATURE IS PER REQUEST: it covers the method, the path, the query, the minute and the hash
     * of the body, so a map taken once and reused is a map that stops working within five minutes
     * and never covers a second body. Call this for each request, with that request's own bytes.
     */
    public fun authHeaders(method: String, url: String, body: ByteArray = ByteArray(0)): Map<String, String> =
        credentialHeaders(method.uppercase(), url.toHttpUrl(), body)

    /**
     * The bearer alone, `Authorization: Bearer sk-st-p-…`.
     *
     * Constant for the life of the token, so unlike [authHeaders] it CAN be handed to a library that
     * takes a fixed header map. That is the whole reason it exists, and the reason the integrations
     * that cannot sign use it.
     */
    public fun bearerHeaders(): Map<String, String> {
        val token = _session.value?.personToken ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    private fun credentialHeaders(method: String, url: HttpUrl, body: ByteArray): Map<String, String> {
        val current = _session.value
        val device = current?.signingDevice
        val signed = when (authMode) {
            AuthMode.SIGNED -> device ?: throw IllegalStateException(
                "AuthMode.SIGNED was asked for and this app holds no signing device. Connect again, " +
                    "or use AuthMode.BEARER.",
            )
            AuthMode.BEARER -> null
            AuthMode.AUTO -> device
        }
        if (signed != null) return signedHeaders(signed, method, url, body)
        val token = current?.personToken ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    private fun signedHeaders(device: SigningDevice, method: String, url: HttpUrl, body: ByteArray): Map<String, String> {
        val timestamp = clock().toString()
        val canonical = Signing.canonicalString(method, Signing.requestPath(url), timestamp, Signing.bodyHash(body))
        val signature = deviceKey.signRaw(canonical.toByteArray(Charsets.UTF_8))
        return mapOf(
            Signing.HEADER_APP to device.appId,
            Signing.HEADER_DEVICE to device.id,
            Signing.HEADER_TIMESTAMP to timestamp,
            Signing.HEADER_SIGNATURE to B64.standard(signature),
        )
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    /** The client WITHOUT our interceptor, for the doors that must not be signed. */
    private val bare: OkHttpClient = httpClient

    private fun isPool(url: HttpUrl): Boolean =
        url.host.equals(siteUrl.host, ignoreCase = true) && url.port == siteUrl.port

    private fun requireToken(): String = _session.value?.personToken
        ?: throw SponsoredException(401, SponsoredException.INVALID_API_KEY, "This app is not connected. Call signIn() first.")

    private suspend fun getJson(path: String, bearer: String?): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(siteUrl.newBuilder().encodedPath(path).build()).get()
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        JSONObject(execute(bare, builder.build()))
    }

    private suspend fun postJson(path: String, body: JSONObject, bearer: String?): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(siteUrl.newBuilder().encodedPath(path).build())
            .post(body.toString().toRequestBody(JSON))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        JSONObject(execute(bare, builder.build()))
    }

    /** A POST that carries whatever credential `/api/v1/...` is using. The beacon's road. */
    private suspend fun postSigned(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(siteUrl.newBuilder().encodedPath(path).build())
            .post(body.toString().toRequestBody(JSON))
            .build()
        JSONObject(execute(client, request))
    }

    /** A GET with no credential at all. The rotation feed is public and is cached at the edge. */
    private suspend fun getPublic(path: String): JSONObject = withContext(Dispatchers.IO) {
        val url = siteUrl.newBuilder().encodedPath(path.substringBefore('?'))
        for ((name, value) in Uris.query(path)) url.addQueryParameter(name, value)
        JSONObject(execute(bare, Request.Builder().url(url.build()).get().build()))
    }

    private fun execute(using: OkHttpClient, request: Request): String {
        using.newCall(request).execute().use { response ->
            val text = response.body?.string()
            // A SESSION THAT IS GONE IS FORGOTTEN HERE, once, rather than by every caller. The app
            // asks `isConnected` again and draws its sign-in button; nothing retries behind its back.
            if (response.code == 401 && _session.value != null) {
                store.clear()
                _session.value = null
            }
            if (!response.isSuccessful) throw SponsoredException.of(response.code, text)
            return text.orEmpty().ifEmpty { "{}" }
        }
    }

    private fun open(activity: Activity, url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, uri)
        } catch (_: ActivityNotFoundException) {
            // NO BROWSER AT ALL is a real state on a locked-down device, and it is not a crash: the
            // person simply cannot connect, and the app should say so rather than die.
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    public companion object {
        public const val DEFAULT_SITE: String = "https://sponsoredtokens.com"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The name the person will see beside this device on their own account page.
         *
         * The MODEL and not the serial, the advertising id or anything else that identifies the
         * phone: the point of the field is that a person looking at a list of connected devices can
         * tell which one is which, and "Pixel 9" does that without being an identifier.
         */
        @JvmStatic
        public fun defaultDeviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            val make = Build.MANUFACTURER?.trim().orEmpty()
            val name = when {
                model.isEmpty() -> make
                make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
                else -> "$make $model"
            }
            return Connect.sanitizeDeviceName(name)
        }
    }
}
