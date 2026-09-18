package com.sponsoredtokens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One fake pool: it remembers what was posted and answers a fixed rotation feed. */
private class FakePool(private val scheduler: () -> Long) {
    val posted = ArrayList<JSONObject>()
    var rotationCalls = 0
    var sponsors = listOf("sp_one", "sp_two", "sp_three")

    fun impressions(): Impressions = Impressions(
        post = { _, body -> posted.add(body); JSONObject().put("accepted", body.getJSONArray("events").length()).put("dropped", 0) },
        get = { _ ->
            rotationCalls++
            JSONObject().put(
                "sponsors",
                org.json.JSONArray().also { array ->
                    sponsors.forEachIndexed { i, id ->
                        array.put(
                            JSONObject()
                                .put("id", id)
                                .put("name", "Sponsor ${i + 1}")
                                .put("url", "https://sponsor$i.example")
                                .put("anonymous", false),
                        )
                    }
                },
            )
        },
        now = scheduler,
    )

    /** Every event of every batch, flattened, as `kind:sponsorId`. */
    fun events(): List<String> = posted.flatMap { body ->
        val array = body.getJSONArray("events")
        (0 until array.length()).map { i ->
            val event = array.getJSONObject(i)
            "${event.getString("kind")}:${event.getString("sponsorId")}"
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImpressionsTest {

    @Test
    fun `nothing is posted until the timer, and then everything at once`() = runTest {
        val pool = FakePool { testScheduler.currentTime }
        val impressions = pool.impressions()
        impressions.start(backgroundScope)

        repeat(3) { impressions.record(ImpressionEvent(ImpressionKind.CARD, "sp_$it")) }
        testScheduler.runCurrent()
        assertEquals("a beacon left before the two seconds were up", 0, pool.posted.size)

        testScheduler.advanceTimeBy(Impressions.FLUSH_EVERY_MILLIS)
        testScheduler.runCurrent()
        assertEquals(1, pool.posted.size)
        assertEquals(3, pool.posted[0].getJSONArray("events").length())
    }

    @Test
    fun `the twentieth event does not wait for the timer`() = runTest {
        val pool = FakePool { testScheduler.currentTime }
        val impressions = pool.impressions()
        impressions.start(backgroundScope)

        repeat(Impressions.MAX_BUFFERED) { impressions.record(ImpressionEvent(ImpressionKind.CARD, "sp_$it")) }
        testScheduler.runCurrent()
        assertEquals(1, pool.posted.size)
        assertEquals(Impressions.MAX_BUFFERED, pool.posted[0].getJSONArray("events").length())
    }

    @Test
    fun `stopping sends what is left, because a backgrounded app may not run again`() = runTest {
        val pool = FakePool { testScheduler.currentTime }
        val impressions = pool.impressions()
        impressions.start(backgroundScope)
        impressions.record(ImpressionEvent(ImpressionKind.LINE, "sp_one"))
        impressions.stop()
        assertEquals(listOf("line:sp_one"), pool.events())
    }

    @Test
    fun `more than fifty events go as several objects, in order, never truncated`() = runTest {
        val pool = FakePool { testScheduler.currentTime }
        val impressions = pool.impressions()
        val events = (0 until 120).map { ImpressionEvent(ImpressionKind.CARD, "sp_$it") }
        val result = impressions.beacon(events)
        assertEquals(3, pool.posted.size)
        assertEquals(listOf(50, 50, 20), pool.posted.map { it.getJSONArray("events").length() })
        assertEquals(120, result.accepted)
        assertEquals((0 until 120).map { "card:sp_$it" }, pool.events())
    }

    @Test
    fun `an event carries the wire spelling of its kind and the time it was shown`() = runTest {
        val pool = FakePool { testScheduler.currentTime }
        pool.impressions().beacon(listOf(ImpressionEvent(ImpressionKind.ROTATION, "sp_x", at = "2026-09-19T10:00:00Z", placement = "composer")))
        val event = pool.posted[0].getJSONArray("events").getJSONObject(0)
        assertEquals("rotation", event.getString("kind"))
        assertEquals("sp_x", event.getString("sponsorId"))
        assertEquals("2026-09-19T10:00:00Z", event.getString("at"))
        assertEquals("composer", event.getString("placement"))
        // Absent rather than null: a key with nothing behind it is a key the door has to decide about.
        assertTrue(!event.has("taskId"))
    }

    @Test
    fun `a failed beacon is never an error the app has to show`() = runTest {
        val impressions = Impressions(
            post = { _, _ -> throw java.io.IOException("offline") },
            get = { _ -> JSONObject() },
            now = { testScheduler.currentTime },
        )
        assertEquals(BeaconResult(0, 0), impressions.beacon(listOf(ImpressionEvent(ImpressionKind.LINE, "sp_one"))))
    }

    @Test
    fun `the rotation feed is fetched once a minute and no oftener`() = runTest {
        val pool = FakePool { testScheduler.currentTime }
        val impressions = pool.impressions()

        assertEquals(3, impressions.rotation().size)
        assertEquals(3, impressions.rotation().size)
        assertEquals("the cache did not hold", 1, pool.rotationCalls)

        testScheduler.advanceTimeBy(Impressions.CACHE_MILLIS + 1)
        impressions.rotation()
        assertEquals(2, pool.rotationCalls)
    }

    @Test
    fun `a different country is a different cache entry`() = runTest {
        val pool = FakePool { testScheduler.currentTime }
        val impressions = pool.impressions()
        impressions.rotation(country = null)
        impressions.rotation(country = "PT")
        assertEquals(2, pool.rotationCalls)
    }

    @Test
    fun `the country is left out of the URL when it is not given, so the edge decides`() = runTest {
        var path: String? = null
        val impressions = Impressions(
            post = { _, _ -> JSONObject() },
            get = { requested ->
                path = requested
                JSONObject().put("sponsors", org.json.JSONArray())
            },
            now = { testScheduler.currentTime },
        )
        impressions.rotation()
        assertEquals("/api/v1/sponsors/rotation?limit=8", path)
        impressions.rotation(country = "PT", limit = 3)
        assertEquals("/api/v1/sponsors/rotation?country=PT&limit=3", path)
    }

    @Test
    fun `a feed that cannot be read is an empty rotation and never an exception`() = runTest {
        // The feed is inventory, not an answer: a request that timed out must not take down the
        // screen the person was reading.
        val failing = Impressions(
            post = { _, _ -> JSONObject() },
            get = { throw java.io.IOException("offline") },
            now = { testScheduler.currentTime },
        )
        assertEquals(emptyList<RotationSponsor>(), failing.rotation())
    }
}
