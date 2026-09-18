package com.sponsoredtokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE FOUR SHAPES `worker/src/sponsored/attribution.ts::footerText` writes, and the one rule about
 * everything else: if the tail is not exactly the pool's line, there is no sponsor and the app reads
 * the headers instead.
 */
class SponsoredFooterTest {

    @Test
    fun `a website sponsor, linked, with an amount`() {
        val split = SponsoredFooter.split("Here is the answer.\n\n— sponsored by [Acme](https://acme.example) · $0.42")
        assertEquals("Here is the answer.", split.body)
        assertEquals("Acme", split.sponsor?.name)
        assertEquals("https://acme.example", split.sponsor?.url)
        assertEquals("$0.42", split.sponsor?.cost)
        assertNull(split.sponsor?.handle)
    }

    @Test
    fun `a profile sponsor, with the platform and the handle`() {
        val split = SponsoredFooter.split("Answer.\n\n— sponsored by Northwind · X @northwind · $1.20")
        assertEquals("Answer.", split.body)
        assertEquals("Northwind", split.sponsor?.name)
        assertEquals("X @northwind", split.sponsor?.handle)
        assertEquals("$1.20", split.sponsor?.cost)
        assertNull(split.sponsor?.url)
    }

    @Test
    fun `an anonymous sponsor is a phrase and not a name`() {
        val split = SponsoredFooter.split("Answer.\n\n— sponsored by an anonymous sponsor · $0.01")
        assertEquals("an anonymous sponsor", split.sponsor?.name)
        assertNull(split.sponsor?.url)
        assertNull(split.sponsor?.sponsorId)
    }

    @Test
    fun `an anonymous fan is credited as a fan of the app they paid for`() {
        val split = SponsoredFooter.split("Answer.\n\n— sponsored by an anonymous fan of Picker · $0.03")
        assertEquals("an anonymous fan of Picker", split.sponsor?.name)
        assertEquals("$0.03", split.sponsor?.cost)
    }

    @Test
    fun `the sponsor id comes out of the hop when the link is one`() {
        val split = SponsoredFooter.split("A.\n\n— sponsored by [Acme](https://sponsoredtokens.com/s/sp_abc) · $0.10")
        assertEquals("sp_abc", split.sponsor?.sponsorId)
    }

    @Test
    fun `the older Task wording still reads`() {
        assertEquals("Acme", SponsoredFooter.split("A.\n\n— Task sponsored by Acme · $0.10").sponsor?.name)
    }

    @Test
    fun `two hyphens and an en dash are accepted`() {
        assertNotNull(SponsoredFooter.split("A.\n\n-- sponsored by Acme · $0.10").sponsor)
        assertNotNull(SponsoredFooter.split("A.\n\n– sponsored by Acme · $0.10").sponsor)
    }

    @Test
    fun `a stray mention in the prose is not a sponsor`() {
        // Putting a stranger's name and a made-up amount under an answer is the one thing this must
        // never do, so the pattern is anchored to the very end and to a blank line before it.
        val text = "I was sponsored by nobody, and here is why.\n\nThe end."
        val split = SponsoredFooter.split(text)
        assertNull(split.sponsor)
        assertEquals(text, split.body)
    }

    @Test
    fun `a footer in the middle is not the tail`() {
        val text = "A.\n\n— sponsored by Acme · $0.10\n\nAnd then more prose."
        assertNull(SponsoredFooter.split(text).sponsor)
    }

    @Test
    fun `a line with no amount is still a sponsor who paid`() {
        val split = SponsoredFooter.split("A.\n\n— sponsored by Acme")
        assertEquals("Acme", split.sponsor?.name)
        assertNull(split.sponsor?.cost)
    }

    // ── The streaming half ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a half-arrived footer never reaches the screen`() {
        val stream = SponsoredFooter.Stream()
        assertEquals("Here is ", stream.push("Here is ").body)
        assertEquals("Here is the answer.", stream.push("the answer.").body)
        // From here on every tick is footer, and the body must not grow by a character of it.
        for (delta in listOf("\n", "\n", "— ", "sponsored", " by ", "[Ac", "me](https://acme.example)", " · $0.42")) {
            assertEquals("the footer leaked at '$delta'", "Here is the answer.", stream.push(delta).body)
        }
        val split = stream.finish()
        assertEquals("Here is the answer.", split.body)
        assertEquals("Acme", split.sponsor?.name)
        assertEquals("$0.42", split.sponsor?.cost)
    }

    @Test
    fun `deltas concatenate to the body`() {
        val stream = SponsoredFooter.Stream()
        val built = StringBuilder()
        for (delta in listOf("One", " two", " three", "\n\n— sponsored by Acme · $0.01")) {
            built.append(stream.push(delta).delta)
        }
        assertEquals("One two three", built.toString())
        assertEquals("One two three", stream.finish().body)
    }

    @Test
    fun `a blank line that turns out to be prose is not withheld forever`() {
        val stream = SponsoredFooter.Stream()
        stream.push("First paragraph.")
        stream.push("\n\n")
        assertEquals("First paragraph.\n\nSecond paragraph.", stream.push("Second paragraph.").body)
        assertNull(stream.finish().sponsor)
    }

    @Test
    fun `the raw text keeps the footer, because the footer is meant to be read`() {
        val stream = SponsoredFooter.Stream()
        stream.push("A.\n\n— sponsored by Acme · $0.01")
        assertEquals("A.\n\n— sponsored by Acme · $0.01", stream.raw())
    }

    @Test
    fun `the lead is exactly the one the pool writes`() {
        assertEquals("— sponsored by ", SponsoredFooter.CREDIT_LEAD)
    }
}
