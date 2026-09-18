# sponsoredtokens-android

Spend the [sponsoredtokens.com](https://sponsoredtokens.com) public pool from an Android app. A
person signs in on our page, presses Connect, and your app gets a credential that spends the pool on
the allowance the pool already gives that person. Every answer ends with a line naming who paid.

```kotlin
val sponsored = SponsoredTokens(
    context = this,
    clientId = "app_yours",
    redirectUri = "com.example.app:/oauth2redirect",
)

sponsored.signIn(activity)                       // a Custom Tab on our page

// in the activity that receives the redirect
when (val result = sponsored.handleRedirect(intent)) {
    is SponsoredTokens.ConnectResult.Connected -> ready(result.session)
    else -> Unit
}

val model = sponsored.defaultModel()             // "sponsored/auto" where your app has a policy
sponsored.client                                 // an OkHttpClient that signs every call
```

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.Yumankind.sponsoredtokens-android:android:0.1.0")
    implementation("com.github.Yumankind.sponsoredtokens-android:compose:0.1.0")      // optional
    implementation("com.github.Yumankind.sponsoredtokens-android:integrations:0.1.0") // optional
}
```

One repository and one dependency. The version is a tag on this repository; JitPack builds it on
first request. `minSdk 26`, Kotlin 2.x, coroutines, and OkHttp. Nothing else is added to your app:
JSON is read with the platform's own `org.json`, and there is no reflection, no code generation and
no annotation processor.

The Maven coordinates the library publishes under are `com.sponsoredtokens:android`. JitPack serves a
multi-module repository under `com.github.<owner>.<repo>`, which is where the lines above come from.

## What happens when somebody connects

```
 1. app     → a Chrome Custom Tab on
              GET https://sponsoredtokens.com/connect
                  ?client_id=…&redirect_uri=…&state=…
                  &code_challenge=…&code_challenge_method=S256
                  &device_key=<base64url of an EC P-256 public JWK>
                  &device_name=<the device model>
 2. person  → signs in with a passkey or a social account, passes Turnstile, presses Connect
 3. browser → <redirect_uri>?code=…&state=…
 4. app     → POST /api/people/connect/token { code, code_verifier, client_id, redirect_uri }
            ← { personToken, expiresAt, person, allowance, signingDevice: { id, appId } }
```

The keypair is generated in the **Android Keystore** and its private half is not exportable: nothing
can copy it off the phone, including this library. What travels in step 1 is the public half.

`redirect_uri` is matched by the worker **as a whole string** and never as a prefix, so the value
registered on your app row, the value in your manifest and the value you pass to `SponsoredTokens`
must be one string.

## The two credentials

From then on the app can call the pool two ways, and it defaults to the first:

| | |
|---|---|
| **Signed** | Four headers over a string this device signs with the Keystore key. **Nothing is stored**, so there is no secret to leak. Used automatically whenever the connect flow returned a `signingDevice`. |
| **Bearer** | `Authorization: Bearer sk-st-p-…`, the person token, 30 days. Simplest, and what a library that cannot take an interceptor gets. |

`AuthMode.AUTO` is the default and picks the first when it can. `AuthMode.SIGNED` insists, and
`AuthMode.BEARER` never signs.

The split by road is fixed and deliberate: **`/api/v1/*` goes by `authMode`**, and
**`/api/people/*` always goes by the person token**, because those doors are the person's and a
signature identifies the app's device.

### The canonical string

```
METHOD \n PATH \n TIMESTAMP \n sha256(body)
```

* `METHOD` is upper case.
* `PATH` is the pathname **and** the query string, so a signature for `/api/v1/models?tier=0` cannot
  be lifted onto `?tier=3`. An empty query contributes no `?`.
* `TIMESTAMP` is unix **seconds**, as a decimal string, inside a five minute window.
* `sha256(body)` is **lowercase hex**, and a request with no body carries the hash of the empty
  string (`e3b0c442…`) rather than an empty field. A `GET` or a `HEAD` always hashes the empty string.

The signature header is **base64 of the raw 64-byte `r‖s` ECDSA P-256 signature, never DER**. Android
signs with `SHA256withECDSA`, which produces DER, so the library converts it; `Der` is that
conversion and `DerTest` proves it against a hundred real signatures a round trip at a time.

The four headers:

```
X-Sponsoredtokens-App:        app_…
X-Sponsoredtokens-Device:     dev_…
X-Sponsoredtokens-Timestamp:  1757340000
X-Sponsoredtokens-Signature:  base64(r‖s)
```

A signed request carries **no `Authorization` header at all**, and the interceptor removes one if it
finds it.

## The public API

```kotlin
package com.sponsoredtokens

class SponsoredTokens(
    context: Context,
    clientId: String,
    redirectUri: String,
    site: String = "https://sponsoredtokens.com",
    store: TokenStore = KeystoreTokenStore(context, clientId),
    httpClient: OkHttpClient = OkHttpClient(),
    authMode: AuthMode = AuthMode.AUTO,
    deviceKey: DeviceKey = AndroidKeystoreDeviceKey(AndroidKeystoreDeviceKey.aliasFor(clientId)),
    deviceName: String = defaultDeviceName(),
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val sessionFlow: StateFlow<Session?>
    val lastAnswer: StateFlow<SponsoredAnswer?>
    val impressions: Impressions
    val client: OkHttpClient          // your client, plus the interceptor
    val apiBaseUrl: String            // <site>/api/v1
    val session: Session?
    val isConnected: Boolean

    fun signIn(activity: Activity)
    suspend fun handleRedirect(intent: Intent): ConnectResult
    suspend fun handleRedirect(uri: Uri): ConnectResult
    suspend fun handleRedirect(uri: String): ConnectResult

    suspend fun me(): Me
    suspend fun models(): ModelShelf
    suspend fun defaultModel(): String
    suspend fun signOut()
    suspend fun forgetDevice()

    fun topUpUrl(state: String = PkcePair.newState()): String
    fun startTopUp(activity: Activity, state: String = PkcePair.newState())

    fun authHeaders(method: String, url: String, body: ByteArray = ByteArray(0)): Map<String, String>
    fun bearerHeaders(): Map<String, String>

    sealed class ConnectResult {
        data class Connected(val session: Session)
        data class ToppedUp(val paid: Boolean)
        data class Refused(val error: String, val description: String?)
        object Ignored
    }
}

enum class AuthMode { AUTO, SIGNED, BEARER }

interface TokenStore { fun read(): String?; fun write(value: String); fun clear() }
class KeystoreTokenStore(context: Context, clientId: String) : TokenStore
class InMemoryTokenStore : TokenStore

interface DeviceKey {
    fun publicJwk(): String
    fun signRaw(message: ByteArray): ByteArray
    fun delete()
}
class AndroidKeystoreDeviceKey(alias: String) : DeviceKey

object Signing {
    const val HEADER_APP; HEADER_DEVICE; HEADER_TIMESTAMP; HEADER_SIGNATURE
    const val SIGNATURE_WINDOW_SECONDS = 300L
    const val EMPTY_BODY_SHA256 = "e3b0c442…"
    fun canonicalString(method: String, path: String, timestamp: String, bodyHashHex: String): String
    fun bodyHash(body: ByteArray?): String
    fun requestPath(url: HttpUrl): String
}

object Connect {
    fun connectUrl(site, clientId, redirectUri, state, codeChallenge, deviceKeyJwk, deviceName): String
    fun topUpUrl(site, clientId, redirectUri, state): String
    fun parseRedirect(uri: String, redirectUri: String): Redirect
    sealed class Redirect { Code(code, state); TopUp(paid, state); Refused(error, description, state); Ignored }
}

data class PkcePair(val verifier: String, val challenge: String) {
    companion object { fun generate(): PkcePair; fun challengeFor(verifier: String): String; fun newState(): String }
}

data class SponsoredAnswer(
    val sponsorName: String?, val sponsorUrl: String?, val sponsorHandle: String?,
    val sponsorProfileUrl: String?, val sponsorId: String?,
    val remainingCents: Int?, val budgetCents: Int?, val resetsAt: String?,
    val paidCents: Int?, val paidBy: PaidBy?, val model: String?,
) { companion object { fun from(headers: Headers): SponsoredAnswer? } }

enum class PaidBy { POOL, APP, PERSON, UNKNOWN }

class SponsoredInterceptor(
    appliesTo: (HttpUrl) -> Boolean,
    headersFor: (String, HttpUrl, ByteArray) -> Map<String, String>,
    onAnswer: (SponsoredAnswer) -> Unit,
) : Interceptor

object SponsoredFooter {
    const val CREDIT_LEAD = "— sponsored by "
    data class Sponsor(val name: String, val url: String?, val handle: String?, val cost: String?)
    data class Split(val body: String, val sponsor: Sponsor?)
    fun split(text: String): Split
    fun trimPartial(text: String): String
    class Stream {
        fun push(delta: String): Chunk       // Chunk(delta, body)
        fun finish(): Split
        fun raw(): String
    }
}

class Impressions {
    suspend fun beacon(events: List<ImpressionEvent>): BeaconResult
    suspend fun record(event: ImpressionEvent)
    suspend fun flush()
    fun start(scope: CoroutineScope)
    suspend fun stop()
    suspend fun rotation(country: String? = null, limit: Int = 8): List<RotationSponsor>
}

class SponsorRotator(
    impressions: Impressions,
    country: String? = null,
    limit: Int = 8,
    holdMillis: Long = 5_000,
    everyMillis: Long = 5_000,
    placement: String? = null,
) {
    val current: StateFlow<ShownSponsor?>
    var onImpression: ((ImpressionEvent) -> Unit)?
    fun show(sponsor: ShownSponsor?, taskId: String? = null)
    fun show(answer: SponsoredAnswer?, footer: SponsoredFooter.Sponsor? = null, taskId: String? = null)
    fun start(scope: CoroutineScope, rotate: Boolean = true)
    fun stop()
}

class SponsoredException(val status: Int, val code: String?, message: String, val allowed: List<String>)
```

```kotlin
package com.sponsoredtokens.compose

@Composable fun SponsoredBy(
    answer: SponsoredAnswer?,
    modifier: Modifier = Modifier,
    footer: SponsoredFooter.Sponsor? = null,
    impressions: Impressions? = null,
    rotate: Boolean = true,
    taskId: String? = null,
    placement: String? = null,
    onImpression: ((ImpressionEvent) -> Unit)? = null,
)

@Composable fun Allowance(answer: SponsoredAnswer?, modifier: Modifier = Modifier)
```

```kotlin
package com.sponsoredtokens.integrations

fun SponsoredTokens.langchain4jModel(model: String): OpenAiChatModel
fun SponsoredTokens.openAiConfig(): OpenAIConfig
fun HttpClientConfig<*>.installSponsoredTokens(sponsored: SponsoredTokens)
```

## Which model

Ask `GET /api/v1/models` and **send an id exactly as it is listed**. The `sponsored/` prefix says who
pays; sending the bare form on a person token is `403 person_token_pool_only`.

```kotlin
val shelf = sponsored.models()
val model = shelf.defaultModel()       // "sponsored/auto" when the app has a model policy
```

`sponsored/auto` means *the app chooses*, by the rule its developer set: a rotation, or one model
when the request has tools and another when it does not. A client that picks from this list never
meets a model refusal, which is the promise the listing makes.

## What every answer says about the money

Ten response headers, on the streamed answer, the whole answer and on a refusal too. The interceptor
reads them into `sponsored.lastAnswer`.

| | |
|---|---|
| `x-sponsored-remaining-cents` | cents left of this week's allowance |
| `x-sponsored-budget-cents` | cents the allowance is for the week |
| `x-sponsored-resets-at` | the Monday it rolls over |
| `x-sponsored-paid-cents` | the person's own paid balance, where your app may spend it |
| `x-sponsored-paid-by` | `pool`, `app` or `person`: which purse paid for this turn |
| `x-sponsored-sponsor` | the sponsor of this turn, when the pool paid |
| `x-sponsored-sponsor-url` | their own site, or the `/s/<id>` hop |
| `x-sponsored-sponsor-handle` | `X @acme`, for a profile sponsor |
| `x-sponsored-sponsor-profile` | the profile that handle lives on |
| `x-sponsored-model` | the bare upstream model this turn used |

**Absent beats empty.** A figure that was not sent is `null`, so `0` always means zero. The sponsor
pair is the pool's alone: an app's purse and a person's own balance draw no sponsor, and this library
never invents one.

## The footer

Every answer the pool pays for ends with one line:

```
\n\n— sponsored by [Acme](https://acme.example) · $0.42
\n\n— sponsored by Acme · X @acme · $0.42
\n\n— sponsored by an anonymous sponsor · $0.42
```

It is meant to be **read**, so show it. Separate it so you can draw it as a chip rather than as a
trailing sentence, and so the message you FILE in your history does not carry it: every turn resends
its history, and a footer left in it is a model that starts imitating the line and a person paying
prompt tokens for our advertising.

```kotlin
val stream = SponsoredFooter.Stream()
// on every delta
text = stream.push(delta).body          // a half-arrived footer never reaches the screen
// at the end
val (body, sponsor) = stream.finish()
```

## The sponsored line is inventory

The line under an answer is the sponsor's whole return, and after it has been seen it can also carry
the others who keep the pool full. Two doors and one rule.

```
POST /api/v1/impressions      { "events": [ { kind, sponsorId, at, placement?, taskId? } ] }   1..50
                              → { "accepted", "dropped" }
GET  /api/v1/sponsors/rotation?country=XX&limit=8      public, cached 60 s
                              → { "sponsors": [ { id, name, url, handle, profileUrl, logoUrl, anonymous } ] }
```

**The rule.** The sponsor who **paid** for this answer is shown first, alone, and beaconed once as
`line` with their id. Only after that line has been visible for **five seconds** may the rotation
begin: one sponsor from the feed at a time, labelled **"also sponsored by"**, beaconed once each as
`rotation`, changing every five seconds, and never replacing the paid line's own first showing. It
pauses while the app is in the background, because nothing is being seen there.

The sponsor's id comes from `x-sponsored-sponsor-id` when the worker sends it, and otherwise from the
`/s/<id>` in the sponsor's URL. **A sponsor with no id is shown and never beaconed** - an anonymous
sponsor names nobody to report.

Beacons are batched: flushed every two seconds, at twenty events, or when the app is stopped,
whichever comes first. A beacon that cannot be sent is dropped in silence; nothing about an
impression is worth an error in front of somebody who only wanted an answer.

```kotlin
SponsoredBy(
    answer = sponsored.lastAnswer.collectAsState().value,
    footer = footerOfThisAnswer,
    impressions = sponsored.impressions,   // omit it and the line is only a credit
    rotate = true,
    placement = "answer",
    onImpression = { analytics.track(it) },
)
```

Omit `country` and let the edge decide: the worker already knows where the request came from, and a
country the app worked out for itself is a second opinion about the same fact.

## Three ways to make the call

### Plain OkHttp, which is the whole library

```kotlin
val request = Request.Builder()
    .url("${sponsored.apiBaseUrl}/chat/completions")
    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
    .build()

sponsored.client.newCall(request).execute()      // signed, and the money headers are read for you
```

This is what `samples/app` does, streaming, in about forty lines.

### LangChain4j

```kotlin
val model = sponsored.langchain4jModel(sponsored.defaultModel())
```

**It uses the bearer, not a signature, and here is why.** `OpenAiChatModel` takes a fixed map of
custom headers, which is right for a bearer - one constant string for thirty days. A device signature
is a different string for every request, because it covers the method, the path, the query, the
minute and the hash of the body; a header map handed over once would stop verifying within five
minutes and would never have covered the body. LangChain4j's OpenAI module builds its own HTTP client
and exposes no per-request hook we can reach, so this adapter uses the bearer and says so rather than
appearing to sign and not signing.

**And one thing to know first:** `langchain4j-open-ai` is a JVM library whose default transport is
`java.net.http.HttpClient`, which does not exist on Android. On a phone it needs a transport of its
own that runs there. On the JVM - a server, a test, a desktop tool sharing this code - it works
unchanged. That is a fact about LangChain4j and not about the pool.

### The community OpenAI Kotlin client

```kotlin
val openAI = OpenAI(sponsored.openAiConfig())
```

Same shape, same limitation, same reason: the hook is a Ktor `HttpClientConfig`, where a plugin can
add a header to every request but cannot be handed the serialized body in time to hash it. The
bearer is read per request, so signing out and reconnecting take effect on the next call.

For signed calls with either library, point it at `sponsored.client`.

## Topping up

Where your app's `people_pay_own_tokens` is on, a person whose week is spent can fill their own
balance without leaving your app:

```kotlin
if (sponsored.me().paid?.allowed == true) sponsored.startTopUp(activity)
```

The return is `?topup=done` or `?topup=cancelled` on the same redirect. **`done` means the payment
was taken, not that the balance has moved**: the credit lands by webhook, seconds later. Re-read
`me()` on return and again a few seconds later until `paid.balanceCents` moves.

## Storage

The person token is encrypted with AES-256-GCM under a key in the AndroidKeyStore and kept as
ciphertext in ordinary preferences. That is what `EncryptedSharedPreferences` does, without taking an
alpha dependency for a Keystore this library already has open. Plug in your own with three methods:

```kotlin
SponsoredTokens(context, clientId, redirectUri, store = MyDataStoreTokenStore())
```

It is unreadable to anything that gets the preferences file alone. It is not proof against code
running as your app, which can ask for the plaintext - the thing that survives even that is the
**signing key**, which nothing can copy, and it is why signed calls are the default.

## Refusals

`SponsoredException` carries the pool's own `code`, which is the part worth branching on.

| code | what to do |
|---|---|
| `invalid_api_key`, `unknown_device`, `device_revoked` | connect again (`needsReconnect` is true) |
| `stale_signature` | the phone's clock is out by more than five minutes |
| `replayed_signature` | never retry a request unchanged; sign each one once |
| `person_token_pool_only` | send the `sponsored/` form the listing gave you |
| `app_model_not_allowed` | `allowed` names the ids that would have worked |
| `blocked` | a ruling, not a shortage. Do not retry and do not reconnect |

## Building it

There is **no `gradlew` in this repository**, on purpose: the wrapper's jar is a binary, and a
`gradlew` whose jar is missing fails with a stack trace rather than an honest message. What is here
is `gradle/wrapper/gradle-wrapper.properties`, the file that pins the version. Generate the rest with
the Gradle you have:

```sh
gradle wrapper --gradle-version 8.11.1
./gradlew :android:assembleRelease :android:testDebugUnitTest
```

CI does exactly that on every push, and so does JitPack (`jitpack.yml`).

### What CI compiles

| job | what |
|---|---|
| **Library, tests and sample** | `:android` and `:compose` release AARs, the JVM unit tests, the sample app's debug APK, and a `publishToMavenLocal` that proves the artifacts publish |
| **Optional adapters** *(advisory)* | `:integrations`, which compiles against LangChain4j and the OpenAI Kotlin client. Allowed to fail without turning the library red: those are third-party APIs on their own release cycles |

### What the tests pin

The canonical string and all four of its details; the body hash including the empty-body rule and
UTF-8; the path with and without a query; the DER to raw conversion, against a hundred real P-256
signatures, round-tripped back through the platform's own verifier; the PKCE pair's shape and RFC
7636's worked example; the connect URL; the redirect parsing for both custom-scheme shapes, including
the top-up outcomes and the ignore rule; percent decoding, where `+` stays a plus; the ten money
headers, absent-beats-empty, and the percent-encoded sponsor name; all four footer shapes and the
streaming one that withholds a half-arrived line; the session round trip; the model shelf's default;
the refusal envelopes; and for the impressions half, the batching at two seconds and at twenty
events, the flush on stop, the sixty-second feed cache, the five-second hold on the paid line, the
one-beacon-per-sponsor-per-answer rule and the restart when a new answer arrives.

## Source

Developed in the sponsoredtokens monorepo, beside the worker that verifies these signatures, and
mirrored here. Issues and pull requests are welcome on this repository; a fix lands in the monorepo
and comes back with the next release.
