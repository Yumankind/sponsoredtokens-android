package com.sponsoredtokens

/**
 * THE SPONSOR'S LINE, lifted off the end of an answer.
 *
 * Every answer the pool pays for ends with one line the pipeline appends
 * (`worker/src/sponsored/attribution.ts::footerText`), in one of four shapes:
 *
 * ```
 *   \n\n— sponsored by [Acme](https://acme.example) · $0.42        a website sponsor
 *   \n\n— sponsored by Acme · X @acme · $0.42                      a profile sponsor
 *   \n\n— sponsored by an anonymous sponsor · $0.42                anonymous
 *   \n\n— sponsored by an anonymous fan of Picker · $0.03          an anonymous fan of one app
 * ```
 *
 * It is the product's whole promise and it is MEANT TO BE READ, so nothing here removes it from what
 * the person sees; it is separated so that the app can draw it as a chip rather than as a trailing
 * sentence of the assistant's prose, and so that the message the app FILES does not carry it. Every
 * harness resends its history each turn, and a footer left in the history is a model that starts
 * imitating it and a person paying prompt tokens for our advertising.
 *
 * `credit` is null unless the tail is EXACTLY that shape. A JSON turn, a tool turn and a refusal all
 * have nowhere to put a line of prose, and the pool says it in the `Sponsored-By` headers instead
 * (see [SponsoredAnswer]). Guessing a sponsor from a stray "sponsored by" in the prose would put a
 * stranger's name and a made-up amount under an answer, which is the one thing this must never do.
 *
 * The parser is the twin of `sponsoredtokens-site/src/lib/chat.ts::splitFooter`, including its
 * tolerance for the older "— Task sponsored by" wording and for a footer that lost its link.
 */
public object SponsoredFooter {

    /** Exactly the lead `worker/src/sponsored/config.ts::FOOTER_LEAD` writes. A twin, pinned by test. */
    public const val CREDIT_LEAD: String = "\u2014 sponsored by "

    /** Who paid, as the line named them. Every field but the name can be absent from a legal line. */
    public data class Sponsor(
        public val name: String,
        /** The sponsor's own site, when the line carried a markdown link. */
        public val url: String? = null,
        /** `X @acme`, the platform label and the handle, exactly as the line prints them. */
        public val handle: String? = null,
        /** As written, `$0.42`. Null when the line carried no amount. */
        public val cost: String? = null,
    ) {
        /** `sp_xxx` out of `/s/sp_xxx`, for an impression beacon. Null when the URL is not one. */
        public val sponsorId: String? get() = SponsoredAnswer.sponsorIdFromUrl(url)
    }

    /** The answer, split into what the person asked for and who paid for it. */
    public data class Split(public val body: String, public val sponsor: Sponsor?)

    /**
     * An em dash or two hyphens, anchored to the END of the text, after a blank line.
     *
     * Anchored, because a model that echoes the line back in the middle of an answer is not the
     * pool's footer. The markdown link is one branch and a bare name the other, because a footer
     * without a URL is still a sponsor who paid. The handle group refuses to start with `$` so that
     * `Name · $0.42` cannot read the amount as a handle and leave the cost null; the look-ahead sits
     * before the optional whitespace, or a zero-width match of that whitespace would let it pass.
     */
    private val FOOTER = Regex(
        "\\n[ \\t]*\\n[ \\t]*(?:\u2014|\u2013|--)[ \\t]*(?:[Tt]ask[ \\t]+)?[Ss]ponsored by[ \\t]+" +
            "(?:\\[([^\\]\\n]+)\\]\\(([^)\\s]+)\\)|([^\\n\u00b7]+?))" +
            "(?:[ \\t]*\u00b7(?![ \\t]*\\$)[ \\t]*([^\\n\u00b7]+?))?" +
            "(?:[ \\t]*\u00b7[ \\t]*(\\$[0-9][0-9,]*(?:\\.[0-9]+)?))?[ \\t]*$",
    )

    /** Body on one side, sponsor on the other. The sponsor is null for anything but the real line. */
    @JvmStatic
    public fun split(text: String): Split {
        val match = FOOTER.find(text) ?: return Split(text, null)
        val (linkName, linkUrl, plainName, handle, cost) = match.destructured
        val name = (linkName.ifEmpty { plainName }).trim()
        if (name.isEmpty()) return Split(text, null)
        return Split(
            body = text.substring(0, match.range.first).trimEnd(),
            sponsor = Sponsor(
                name = name,
                url = linkUrl.trim().ifEmpty { null },
                handle = handle.trim().ifEmpty { null },
                cost = cost.trim().ifEmpty { null },
            ),
        )
    }

    /**
     * The same text with a HALF-ARRIVED footer taken off the end.
     *
     * The footer lands character by character like everything else, and a naive render flashes
     * "— sponsored by [Nor" into the bottom of the prose and then takes it away again. A tail that
     * is a proper prefix of the lead is dropped, and so is a tail that has got past the lead, since
     * the only thing that follows the lead is the rest of the footer. The twin is
     * `sponsoredtokens-site/src/lib/chat.ts::trimPartialFooter`.
     */
    @JvmStatic
    public fun trimPartial(text: String): String {
        val blank = text.lastIndexOf("\n\n")
        if (blank == -1) return text
        // Only the LAST block, and only while it is still one line: two lines after a blank line is
        // prose that happens to start with a dash, not a footer.
        val tail = text.substring(blank + 2)
        if (tail.isEmpty() || tail.contains('\n')) return text
        val dashed = tail.replaceFirst(Regex("^(?:\u2013|--)"), "\u2014")
        val started =
            if (dashed.length <= CREDIT_LEAD.length) CREDIT_LEAD.startsWith(dashed) else dashed.startsWith(CREDIT_LEAD)
        return if (started) text.substring(0, blank) else text
    }

    /**
     * A streaming reader: feed it deltas, show what it gives back, ask it for the sponsor at the end.
     *
     * It withholds a footer that is still arriving and never emits one halfway. Two ways to use what
     * [push] answers, and they cannot disagree:
     *
     *   · `chunk.body` is the WHOLE safe text so far. Setting a text view to it is always right.
     *   · `chunk.delta` is what to append. It is empty on a tick that only added footer characters,
     *     and on the tick where a trailing blank line turns out to have been the footer's own it
     *     cannot un-append what was already shown - which is why `body` exists and is exact.
     */
    public class Stream {
        private val all = StringBuilder()
        private var emitted = 0

        /** What is safe to show after this delta. */
        public data class Chunk(public val delta: String, public val body: String)

        public fun push(delta: String): Chunk {
            all.append(delta)
            val safe = withholdBlankTail(trimPartial(all.toString()))
            if (safe.length <= emitted) return Chunk("", safe)
            val out = safe.substring(emitted)
            emitted = safe.length
            return Chunk(out, safe)
        }

        /** The finished answer: the body without the footer, and the sponsor the footer named. */
        public fun finish(): Split = split(all.toString())

        /** Everything fed in, footer included. What to show, and never what to file. */
        public fun raw(): String = all.toString()

        /**
         * Hold back a trailing newline or two, because they may be the footer's own blank line.
         *
         * [trimPartial] cannot see a footer until its blank line is complete AND something follows
         * it, so on the tick that carries the first `\n` it has nothing to go on and returns the
         * text whole. Emitting those characters and then wanting them back is the flicker this class
         * exists to prevent, so a tail of at most two newlines waits one tick. A stream that really
         * did end in blank lines loses them from the last [Chunk] and not from [finish], which reads
         * the whole text.
         */
        private fun withholdBlankTail(text: String): String {
            var end = text.length
            var newlines = 0
            while (end > 0 && text[end - 1] == '\n' && newlines < 2) {
                end--
                newlines++
            }
            return if (newlines == 0) text else text.substring(0, end)
        }
    }
}
