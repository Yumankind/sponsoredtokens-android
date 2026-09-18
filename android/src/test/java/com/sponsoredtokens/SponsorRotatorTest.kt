package com.sponsoredtokens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FIVE SECOND RULE, AND WHO IS BEACONED WHEN.
 *
 * The paid line is a statement about who bought this answer; everything after it is inventory. These
 * tests are the difference between the two, on virtual time, with no clock and no device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SponsorRotatorTest {

    private val acme = ShownSponsor(id = "sp_acme", name = "Acme", url = "https://acme.example", paid = true)

    private fun pool(scheduler: () -> Long, ids: List<String> = listOf("sp_one", "sp_two")): Pair<Impressions, ArrayList<JSONObject>> {
        val posted = ArrayList<JSONObject>()
        val impressions = Impressions(
            post = { _, body -> posted.add(body); JSONObject().put("accepted", 0).put("dropped", 0) },
            get = {
                JSONObject().put(
                    "sponsors",
                    JSONArray().also { array ->
                        ids.forEach { id -> array.put(JSONObject().put("id", id).put("name", id.uppercase())) }
                    },
                )
            },
            now = scheduler,
        )
        return impressions to posted
    }

    private fun ArrayList<JSONObject>.events(): List<String> = flatMap { body ->
        val array = body.getJSONArray("events")
        (0 until array.length()).map { "${array.getJSONObject(it).getString("kind")}:${array.getJSONObject(it).getString("sponsorId")}" }
    }

    @Test
    fun `the paid line holds the screen for five seconds before anything else`() = runTest {
        val (impressions, _) = pool { testScheduler.currentTime }
        val rotator = SponsorRotator(impressions)
        rotator.show(acme)
        rotator.start(backgroundScope)
        testScheduler.runCurrent()

        assertEquals("Acme", rotator.current.value?.name)
        assertEquals(true, rotator.current.value?.paid)

        testScheduler.advanceTimeBy(4_999)
        testScheduler.runCurrent()
        assertEquals("the rotation started before the paid line had its five seconds", "Acme", rotator.current.value?.name)

        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertEquals("SP_ONE", rotator.current.value?.name)
        assertEquals(false, rotator.current.value?.paid)
    }

    @Test
    fun `the rotation moves on every five seconds and cycles`() = runTest {
        val (impressions, _) = pool { testScheduler.currentTime }
        val rotator = SponsorRotator(impressions)
        rotator.show(acme)
        rotator.start(backgroundScope)
        testScheduler.advanceTimeBy(SponsorRotator.PAID_HOLD_MILLIS + 1)
        testScheduler.runCurrent()
        assertEquals("SP_ONE", rotator.current.value?.name)

        testScheduler.advanceTimeBy(SponsorRotator.ROTATE_EVERY_MILLIS)
        testScheduler.runCurrent()
        assertEquals("SP_TWO", rotator.current.value?.name)

        testScheduler.advanceTimeBy(SponsorRotator.ROTATE_EVERY_MILLIS)
        testScheduler.runCurrent()
        assertEquals("the feed did not cycle", "SP_ONE", rotator.current.value?.name)
    }

    @Test
    fun `the paid line is beaconed once as a line and each rotation once as a rotation`() = runTest {
        val (impressions, posted) = pool { testScheduler.currentTime }
        val rotator = SponsorRotator(impressions)
        rotator.show(acme)
        rotator.start(backgroundScope)
        // Two full cycles of a two-sponsor feed: the same ids come round again and must not be
        // beaconed twice for one answer.
        testScheduler.advanceTimeBy(SponsorRotator.PAID_HOLD_MILLIS + 4 * SponsorRotator.ROTATE_EVERY_MILLIS + 1)
        testScheduler.runCurrent()
        impressions.stop()

        assertEquals(listOf("line:sp_acme", "rotation:sp_one", "rotation:sp_two"), posted.events())
    }

    @Test
    fun `the dedupe is per answer, so the next answer's line is beaconed again`() = runTest {
        val (impressions, posted) = pool { testScheduler.currentTime }
        val rotator = SponsorRotator(impressions)
        rotator.show(acme)
        rotator.start(backgroundScope)
        testScheduler.runCurrent()

        // The same answer, drawn again by a recomposition: nothing happens.
        rotator.show(acme)
        testScheduler.runCurrent()

        // A new answer with the same sponsor IS a new showing, and is owed its own line.
        rotator.show(acme.copy(), taskId = "turn-2")
        testScheduler.runCurrent()
        impressions.stop()

        assertEquals(listOf("line:sp_acme", "line:sp_acme"), posted.events())
    }

    @Test
    fun `a new answer restarts the five seconds with its own sponsor`() = runTest {
        val (impressions, _) = pool { testScheduler.currentTime }
        val rotator = SponsorRotator(impressions)
        rotator.show(acme)
        rotator.start(backgroundScope)
        testScheduler.advanceTimeBy(4_000)
        testScheduler.runCurrent()

        val other = ShownSponsor(id = "sp_northwind", name = "Northwind", paid = true)
        rotator.show(other, taskId = "turn-2")
        testScheduler.runCurrent()
        assertEquals("Northwind", rotator.current.value?.name)

        // Four seconds had already passed for the first line; they count for nothing here.
        testScheduler.advanceTimeBy(4_999)
        testScheduler.runCurrent()
        assertEquals("Northwind", rotator.current.value?.name)
        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertEquals(false, rotator.current.value?.paid)
    }

    @Test
    fun `rotate false shows and beacons the paid line and nothing else`() = runTest {
        val (impressions, posted) = pool { testScheduler.currentTime }
        val rotator = SponsorRotator(impressions)
        rotator.show(acme)
        rotator.start(backgroundScope, rotate = false)
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        impressions.stop()

        assertEquals("Acme", rotator.current.value?.name)
        assertEquals(listOf("line:sp_acme"), posted.events())
    }

    @Test
    fun `the sponsor who paid is not shown again as one of the others`() = runTest {
        val (impressions, posted) = pool({ testScheduler.currentTime }, ids = listOf("sp_acme", "sp_two"))
        val rotator = SponsorRotator(impressions)
        rotator.show(acme)
        rotator.start(backgroundScope)
        testScheduler.advanceTimeBy(SponsorRotator.PAID_HOLD_MILLIS + 1)
        testScheduler.runCurrent()
        assertEquals("SP_TWO", rotator.current.value?.name)
        impressions.stop()
        assertEquals(listOf("line:sp_acme", "rotation:sp_two"), posted.events())
    }

    @Test
    fun `an anonymous sponsor is shown and never beaconed, because there is nobody to report`() = runTest {
        val (impressions, posted) = pool({ testScheduler.currentTime }, ids = emptyList())
        val rotator = SponsorRotator(impressions)
        rotator.show(ShownSponsor(id = null, name = "an anonymous sponsor", paid = true))
        rotator.start(backgroundScope)
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()
        impressions.stop()

        assertEquals("an anonymous sponsor", rotator.current.value?.name)
        assertEquals(emptyList<String>(), posted.events())
    }

    @Test
    fun `stopping the scope stops the cycling`() = runTest {
        val (impressions, posted) = pool { testScheduler.currentTime }
        val rotator = SponsorRotator(impressions)
        rotator.show(acme)
        rotator.start(backgroundScope)
        testScheduler.advanceTimeBy(SponsorRotator.PAID_HOLD_MILLIS + 1)
        testScheduler.runCurrent()
        rotator.stop()
        val shown = rotator.current.value?.name

        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals("the rotator kept going after stop()", shown, rotator.current.value?.name)
        impressions.stop()
        assertTrue(posted.events().size <= 2)
    }

    @Test
    fun `every beacon is offered to the callback as well`() = runTest {
        val (impressions, _) = pool { testScheduler.currentTime }
        val seen = ArrayList<ImpressionEvent>()
        val rotator = SponsorRotator(impressions, placement = "composer")
        rotator.onImpression = { seen.add(it) }
        rotator.show(acme, taskId = "turn-1")
        rotator.start(backgroundScope)
        testScheduler.advanceTimeBy(SponsorRotator.PAID_HOLD_MILLIS + 1)
        testScheduler.runCurrent()

        assertEquals(ImpressionKind.LINE, seen[0].kind)
        assertEquals("sp_acme", seen[0].sponsorId)
        assertEquals("composer", seen[0].placement)
        assertEquals("turn-1", seen[0].taskId)
        assertEquals(ImpressionKind.ROTATION, seen[1].kind)
    }

    @Test
    fun `the paid sponsor is read off the headers first and the footer second`() {
        val answer = SponsoredAnswer(sponsorName = "Acme", sponsorUrl = "https://sponsoredtokens.com/s/sp_abc")
        val footer = SponsoredFooter.Sponsor(name = "Ignored", url = "https://other.example", cost = "$0.42")
        val shown = ShownSponsor.paidBy(answer, footer)!!
        assertEquals("Acme", shown.name)
        assertEquals("sp_abc", shown.id)
        assertEquals(true, shown.paid)

        // A turn with no headers at all still has the line the person can see.
        val fromFooter = ShownSponsor.paidBy(null, footer)!!
        assertEquals("Ignored", fromFooter.name)
        assertNull(ShownSponsor.paidBy(null, null))
    }

    @Test
    fun `the lead says which kind of line this is`() {
        assertEquals("sponsored by", acme.lead)
        assertEquals("also sponsored by", acme.copy(paid = false).lead)
    }
}
