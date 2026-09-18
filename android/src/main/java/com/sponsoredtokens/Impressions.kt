package com.sponsoredtokens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** What was on screen. `line` is the sponsor who paid; `rotation` is one from the feed. */
public enum class ImpressionKind(public val wire: String) {
    /** The paid line: "sponsored by X", the sponsor who actually paid for that answer. */
    LINE("line"),

    /** One from the rotation feed, shown after the paid line has had its five seconds. */
    ROTATION("rotation"),

    /** A card or a banner somewhere else in the app. */
    CARD("card"),
}

/**
 * ONE SHOWING OF ONE SPONSOR.
 *
 * `at` is when it was shown and not when the beacon left, because beacons are batched and a batch
 * that waited two seconds for company must not move the times of everything in it.
 */
public data class ImpressionEvent(
    public val kind: ImpressionKind,
    public val sponsorId: String,
    public val at: String = Instant.now().toString(),
    /** Where in the app. The pool's own vocabulary; null where the app has only one place. */
    public val placement: String? = null,
    /** The turn this showing belongs to, where the app has an id for one. */
    public val taskId: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.wire)
        put("sponsorId", sponsorId)
        put("at", at)
        placement?.let { put("placement", it) }
        taskId?.let { put("taskId", it) }
    }
}

/** What `POST /api/v1/impressions` answered. */
public data class BeaconResult(public val accepted: Int, public val dropped: Int)

/** One sponsor of the rotation feed. `anonymous` is false for every row it carries. */
public data class RotationSponsor(
    public val id: String,
    public val name: String,
    public val url: String? = null,
    /** `X @acme`, spelled as the footer prints it. */
    public val handle: String? = null,
    public val profileUrl: String? = null,
    public val logoUrl: String? = null,
    public val anonymous: Boolean = false,
) {
    internal companion object {
        fun of(json: JSONObject): RotationSponsor? {
            val id = json.optStringOrNull("id") ?: return null
            val name = json.optStringOrNull("name") ?: return null
            return RotationSponsor(
                id = id,
                name = name,
                url = json.optStringOrNull("url"),
                handle = json.optStringOrNull("handle"),
                profileUrl = json.optStringOrNull("profileUrl"),
                logoUrl = json.optStringOrNull("logoUrl"),
                anonymous = json.optBoolean("anonymous", false),
            )
        }
    }
}

/**
 * THE SPONSORED LINE IS INVENTORY, and this is the half of that which talks to the pool.
 *
 * Two doors, and one rule about how they are used.
 *
 *   · `POST /api/v1/impressions` - what was shown, in batches of 1 to 50, with whatever credential
 *     the app already sends. Beacons are NOT worth a round trip each: they are buffered and flushed
 *     every two seconds, or at twenty events, or when the app goes to the background, whichever
 *     comes first. A beacon that is lost is lost; nothing about an impression is worth delaying an
 *     answer or holding a wake lock for.
 *   · `GET /api/v1/sponsors/rotation` - the feed, public, no credential, cached sixty seconds at the
 *     edge and for the same sixty here, so a screen that starts a rotator twice makes one request.
 *
 * **THE PAID LINE IS NOT INVENTORY.** The sponsor who paid for an answer is shown first, on their
 * own, and beaconed as `line`; the rotation may only begin after that line has been visible for five
 * seconds, and it never replaces the paid line's first showing. That is [SponsorRotator]'s rule and
 * it is the whole reason the rotator exists rather than a timer in a composable.
 */
public class Impressions internal constructor(
    private val post: suspend (path: String, body: JSONObject) -> JSONObject,
    private val get: suspend (path: String) -> JSONObject,
    /** Milliseconds, monotonic enough for a cache. Injected so a test can drive it with virtual time. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Mutex()
    private val buffer = ArrayList<ImpressionEvent>()
    private var flusher: Job? = null
    private var cached: List<RotationSponsor>? = null
    private var cachedKey: String? = null
    private var cachedAt: Long = 0

    /**
     * Send these now. 1 to 50 an object; a longer list is sent as several, in order.
     *
     * Every failure is swallowed into a zero result rather than thrown: the caller is a UI drawing a
     * line of text, and an impression that could not be reported is not a reason for an app to show
     * an error to somebody who only wanted an answer.
     */
    public suspend fun beacon(events: List<ImpressionEvent>): BeaconResult {
        if (events.isEmpty()) return BeaconResult(0, 0)
        var accepted = 0
        var dropped = 0
        for (batch in events.chunked(MAX_PER_POST)) {
            val body = JSONObject().put(
                "events",
                JSONArray().also { array -> batch.forEach { array.put(it.toJson()) } },
            )
            val answer = runCatching { post(IMPRESSIONS_PATH, body) }.getOrNull() ?: continue
            accepted += answer.optIntOrNull("accepted") ?: 0
            dropped += answer.optIntOrNull("dropped") ?: 0
        }
        return BeaconResult(accepted, dropped)
    }

    /** Buffer one. It leaves within two seconds, or at once if this is the twentieth. */
    public suspend fun record(event: ImpressionEvent) {
        val full = lock.withLock {
            buffer.add(event)
            buffer.size >= MAX_BUFFERED
        }
        if (full) flush()
    }

    /** Send whatever is buffered. Call it on `ON_STOP`: a backgrounded app may not run again. */
    public suspend fun flush() {
        val batch = lock.withLock {
            if (buffer.isEmpty()) return
            ArrayList(buffer).also { buffer.clear() }
        }
        beacon(batch)
    }

    /**
     * Start the two-second flusher in `scope`.
     *
     * Idempotent, because a screen that recreates its composables must not end up with two of them.
     * Cancelling `scope` stops it, which is why nothing here holds a scope of its own: a beacon
     * timer that outlives the screen it belongs to is a timer nobody turns off.
     */
    public fun start(scope: CoroutineScope) {
        if (flusher?.isActive == true) return
        flusher = scope.launch {
            while (isActive) {
                delay(FLUSH_EVERY_MILLIS)
                flush()
            }
        }
    }

    /** Stop the flusher and send what is left. */
    public suspend fun stop() {
        flusher?.cancel()
        flusher = null
        flush()
    }

    /**
     * The rotation feed, cached for sixty seconds by country and size.
     *
     * OMIT THE COUNTRY AND LET THE EDGE DECIDE. The worker already knows where the request came from
     * and answers the board for it; a country this app worked out for itself is a second opinion
     * about the same fact, and the one time the two disagree is the one time it matters.
     */
    @JvmOverloads
    public suspend fun rotation(country: String? = null, limit: Int = DEFAULT_LIMIT): List<RotationSponsor> {
        val key = "${country.orEmpty()}:$limit"
        lock.withLock {
            val hit = cached
            if (hit != null && cachedKey == key && now() - cachedAt < CACHE_MILLIS) return hit
        }
        val path = Uris.build(
            ROTATION_PATH,
            linkedMapOf("country" to country, "limit" to limit.toString()),
        )
        val json = runCatching { get(path) }.getOrNull() ?: return cached.orEmpty()
        val rows = json.optJSONArray("sponsors") ?: JSONArray()
        val sponsors = (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { RotationSponsor.of(it) } }
        lock.withLock {
            cached = sponsors
            cachedKey = key
            cachedAt = now()
        }
        return sponsors
    }

    public companion object {
        public const val IMPRESSIONS_PATH: String = "/api/v1/impressions"
        public const val ROTATION_PATH: String = "/api/v1/sponsors/rotation"

        /** The door's own ceiling. A longer list is sent as several objects, never truncated. */
        public const val MAX_PER_POST: Int = 50

        /** Flush at this many buffered events without waiting for the timer. */
        public const val MAX_BUFFERED: Int = 20

        public const val FLUSH_EVERY_MILLIS: Long = 2_000

        /** The same sixty seconds the edge caches the feed for. Two caches, one number. */
        public const val CACHE_MILLIS: Long = 60_000

        public const val DEFAULT_LIMIT: Int = 8
    }
}
