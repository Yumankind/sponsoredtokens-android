package com.sponsoredtokens

import okhttp3.Headers

/** Which purse paid for one turn. The three steps of the person ladder, in the wire's vocabulary. */
public enum class PaidBy {
    /** The public pool, on the person's own weekly allowance. The only case that draws a sponsor. */
    POOL,

    /** The app's own purse, because the week was spent. */
    APP,

    /** The person's own paid balance, which the app has to have opted into. */
    PERSON,

    /** A value this version does not know. Absent beats empty, so a name we cannot read is kept. */
    UNKNOWN,
    ;

    public companion object {
        internal fun of(raw: String?): PaidBy? = when (raw?.lowercase()) {
            null -> null
            "pool" -> POOL
            "app" -> APP
            "person" -> PERSON
            else -> UNKNOWN
        }
    }
}

/**
 * WHAT AN ANSWER SAID ABOUT THE MONEY - the ten `x-sponsored-*` headers, read once.
 *
 * `HANDOFF-people.md` §5b: they are on the streamed answer, on the whole answer, on all three wire
 * formats and on a refusal too, because a client that has just been told its week is spent is the
 * one that most needs to know when the week comes back. The names are
 * `worker/src/sponsored/spend-headers.ts::SPEND_HEADERS` and this is their Android twin.
 *
 * Three rules the worker states and this file therefore obeys:
 *
 *   · **ABSENT BEATS EMPTY.** A fact that is not known is not sent, so a null here means "not told"
 *     and `0` means zero. Nothing is defaulted.
 *   · **The sponsor pair is the pool's alone.** An app's purse and a person's own balance draw no
 *     sponsor, so `paidBy` of `app` or `person` carries no name, and this class does not invent one.
 *   · **Values are percent-encoded UTF-8.** A sponsor's display name is third-party input, so the
 *     worker's `asciiHeaderValue` encodes everything outside printable ASCII and encodes `%` with
 *     it. `Uris.decode` is `decodeURIComponent`, which is the exact inverse.
 */
public data class SponsoredAnswer(
    public val sponsorName: String? = null,
    public val sponsorUrl: String? = null,
    public val sponsorHandle: String? = null,
    public val sponsorProfileUrl: String? = null,
    /** The sponsor's own id, for an impression beacon. From the header, or out of `/s/<id>`. */
    public val sponsorId: String? = null,
    public val remainingCents: Int? = null,
    public val budgetCents: Int? = null,
    /** ISO 8601 UTC, the Monday the week rolls over. Kept as written; nothing here parses a date. */
    public val resetsAt: String? = null,
    public val paidCents: Int? = null,
    public val paidBy: PaidBy? = null,
    /** The BARE upstream model this turn actually used. On every paid answer, `auto` or not. */
    public val model: String? = null,
) {

    /** True when the pool paid and there is a name to show. The condition the credit line is drawn on. */
    public val hasSponsor: Boolean get() = !sponsorName.isNullOrEmpty()

    public companion object {
        public const val REMAINING_CENTS: String = "x-sponsored-remaining-cents"
        public const val BUDGET_CENTS: String = "x-sponsored-budget-cents"
        public const val RESETS_AT: String = "x-sponsored-resets-at"
        public const val PAID_CENTS: String = "x-sponsored-paid-cents"
        public const val PAID_BY: String = "x-sponsored-paid-by"
        public const val SPONSOR: String = "x-sponsored-sponsor"
        public const val SPONSOR_URL: String = "x-sponsored-sponsor-url"
        public const val SPONSOR_HANDLE: String = "x-sponsored-sponsor-handle"
        public const val SPONSOR_PROFILE: String = "x-sponsored-sponsor-profile"
        public const val MODEL: String = "x-sponsored-model"

        /**
         * The sponsor's id, when the worker sends it.
         *
         * NOT one of §5b's ten: it belongs to the impressions contract, and where it is absent the
         * id is taken out of `sponsorUrl`'s own `/s/<id>` hop, which every non-anonymous sponsor
         * has. A beacon with no id is simply not sent.
         */
        public const val SPONSOR_ID: String = "x-sponsored-sponsor-id"

        /** `Sponsored-By` and its URL: the same fact in a second place, older than the ten. */
        public const val SPONSORED_BY: String = "Sponsored-By"
        public const val SPONSORED_BY_URL: String = "Sponsored-By-Url"

        private val SPONSOR_ID_IN_URL = Regex("/s/([A-Za-z0-9_-]+)")

        /** `https://sponsoredtokens.com/s/sp_abc` → `sp_abc`. Anything else is not a sponsor id. */
        @JvmStatic
        public fun sponsorIdFromUrl(url: String?): String? =
            url?.let { SPONSOR_ID_IN_URL.find(it)?.groupValues?.get(1) }

        /** From a response's headers, or null when the answer carried none of them. */
        @JvmStatic
        public fun from(headers: Headers): SponsoredAnswer? = parse { headers[it] }

        /**
         * From anything that can look a header up, case-insensitively.
         *
         * Taking a lookup rather than a `Headers` is what lets `SponsoredAnswerTest` pin every rule
         * in this file with a plain map, on a JVM, with no OkHttp response to build.
         */
        @JvmStatic
        public fun parse(get: (String) -> String?): SponsoredAnswer? {
            fun text(name: String): String? = get(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { Uris.decode(it) }
            fun cents(name: String): Int? = get(name)?.trim()?.toIntOrNull()

            val sponsorUrl = text(SPONSOR_URL) ?: text(SPONSORED_BY_URL)
            val answer = SponsoredAnswer(
                // `Sponsored-By` is the fallback and never the source: a JSON turn and a tool turn
                // have nowhere to put a line of prose, and that pair is where the pool says it then.
                sponsorName = text(SPONSOR) ?: text(SPONSORED_BY),
                sponsorUrl = sponsorUrl,
                sponsorHandle = text(SPONSOR_HANDLE),
                sponsorProfileUrl = text(SPONSOR_PROFILE),
                sponsorId = text(SPONSOR_ID) ?: sponsorIdFromUrl(sponsorUrl),
                remainingCents = cents(REMAINING_CENTS),
                budgetCents = cents(BUDGET_CENTS),
                resetsAt = text(RESETS_AT),
                paidCents = cents(PAID_CENTS),
                paidBy = PaidBy.of(get(PAID_BY)?.trim()?.takeIf { it.isNotEmpty() }),
                model = text(MODEL),
            )
            return if (answer == EMPTY) null else answer
        }

        private val EMPTY = SponsoredAnswer()
    }
}
