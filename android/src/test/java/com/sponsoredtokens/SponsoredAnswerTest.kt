package com.sponsoredtokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ten money headers, read the way `HANDOFF-people.md` §5b says they are written. */
class SponsoredAnswerTest {

    private fun parse(vararg pairs: Pair<String, String>): SponsoredAnswer? {
        val map = pairs.toMap().mapKeys { it.key.lowercase() }
        return SponsoredAnswer.parse { map[it.lowercase()] }
    }

    @Test
    fun `a pool answer with a website sponsor`() {
        val answer = parse(
            "x-sponsored-remaining-cents" to "363",
            "x-sponsored-budget-cents" to "500",
            "x-sponsored-resets-at" to "2026-09-21T00:00:00.000Z",
            "x-sponsored-paid-by" to "pool",
            "x-sponsored-sponsor" to "Acme",
            "x-sponsored-sponsor-url" to "https://sponsoredtokens.com/s/sp_abc",
            "x-sponsored-model" to "anthropic/claude-haiku-4.5",
        )!!
        assertEquals(363, answer.remainingCents)
        assertEquals(500, answer.budgetCents)
        assertEquals("2026-09-21T00:00:00.000Z", answer.resetsAt)
        assertEquals(PaidBy.POOL, answer.paidBy)
        assertEquals("Acme", answer.sponsorName)
        assertEquals("anthropic/claude-haiku-4.5", answer.model)
        // No `x-sponsored-sponsor-id` was sent, so the id comes out of the `/s/<id>` hop.
        assertEquals("sp_abc", answer.sponsorId)
        // ABSENT BEATS EMPTY. Nothing was said about the paid balance, so nothing is reported.
        assertNull(answer.paidCents)
    }

    @Test
    fun `an explicit sponsor id wins over the one in the URL`() {
        val answer = parse(
            "x-sponsored-sponsor" to "Acme",
            "x-sponsored-sponsor-id" to "sp_explicit",
            "x-sponsored-sponsor-url" to "https://sponsoredtokens.com/s/sp_abc",
        )!!
        assertEquals("sp_explicit", answer.sponsorId)
    }

    @Test
    fun `a profile sponsor carries the handle and the profile it lives on`() {
        val answer = parse(
            "x-sponsored-paid-by" to "pool",
            "x-sponsored-sponsor" to "Bruno",
            "x-sponsored-sponsor-handle" to "X @youfoundbruno",
            "x-sponsored-sponsor-profile" to "https://x.com/youfoundbruno",
        )!!
        assertEquals("X @youfoundbruno", answer.sponsorHandle)
        assertEquals("https://x.com/youfoundbruno", answer.sponsorProfileUrl)
    }

    @Test
    fun `values are percent decoded, and a percent sign survives`() {
        // The worker's `asciiHeaderValue` encodes everything outside printable ASCII and encodes `%`
        // with it, so `decodeURIComponent` on any value returns the original string, always.
        val answer = parse("x-sponsored-sponsor" to "Caf%C3%A9%20100%25")!!
        assertEquals("Café 100%", answer.sponsorName)
    }

    @Test
    fun `zero is zero and missing is missing`() {
        val answer = parse("x-sponsored-remaining-cents" to "0")!!
        assertEquals(0, answer.remainingCents)
        assertNull(answer.budgetCents)
    }

    @Test
    fun `an answer with none of them is null, not an empty one`() {
        assertNull(parse("content-type" to "application/json"))
    }

    @Test
    fun `a purse that draws no sponsor carries no name, and none is invented`() {
        val answer = parse("x-sponsored-paid-by" to "person", "x-sponsored-paid-cents" to "1200")!!
        assertEquals(PaidBy.PERSON, answer.paidBy)
        assertEquals(1200, answer.paidCents)
        assertNull(answer.sponsorName)
        assertEquals(false, answer.hasSponsor)
    }

    @Test
    fun `a purse this version does not know is kept rather than dropped`() {
        assertEquals(PaidBy.UNKNOWN, parse("x-sponsored-paid-by" to "something-new")!!.paidBy)
    }

    @Test
    fun `the older Sponsored-By pair is the fallback for a turn with no prose`() {
        // A JSON turn and a tool turn have nowhere to put a line, and the pool says it here instead.
        val answer = parse("Sponsored-By" to "Acme", "Sponsored-By-Url" to "https://sponsoredtokens.com/s/sp_xyz")!!
        assertEquals("Acme", answer.sponsorName)
        assertEquals("sp_xyz", answer.sponsorId)
    }

    @Test
    fun `a sponsor URL that is not a hop yields no id`() {
        assertNull(SponsoredAnswer.sponsorIdFromUrl("https://acme.example"))
        assertNull(SponsoredAnswer.sponsorIdFromUrl(null))
    }
}
