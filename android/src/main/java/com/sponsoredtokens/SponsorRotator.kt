package com.sponsoredtokens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ONE SPONSOR, ON SCREEN, RIGHT NOW.
 *
 * `paid` is the whole distinction: the sponsor who actually paid for this answer, or one of the
 * others. Everything the UI says about them follows from it, starting with [lead].
 */
public data class ShownSponsor(
    /** Null for a sponsor with no id - an anonymous one. Shown, and never beaconed. */
    public val id: String?,
    public val name: String,
    public val url: String? = null,
    /** `X @acme`, spelled as the footer prints it. */
    public val handle: String? = null,
    public val profileUrl: String? = null,
    public val logoUrl: String? = null,
    /** True for the sponsor who paid for this answer. */
    public val paid: Boolean,
) {
    /**
     * "sponsored by" or "also sponsored by".
     *
     * The second word is not decoration. The paid line is a statement of fact about who bought this
     * answer; a rotation line is an advertisement. Saying "also" is how a reader can tell them
     * apart, and it is the reason the rotation may never replace the paid line's own first showing.
     */
    public val lead: String get() = if (paid) "sponsored by" else "also sponsored by"

    public companion object {
        internal fun of(sponsor: RotationSponsor, paid: Boolean): ShownSponsor = ShownSponsor(
            id = sponsor.id,
            name = sponsor.name,
            url = sponsor.url,
            handle = sponsor.handle,
            profileUrl = sponsor.profileUrl,
            logoUrl = sponsor.logoUrl,
            paid = paid,
        )

        /**
         * The sponsor who paid, out of an answer's headers and, failing that, its footer.
         *
         * Headers first, because they are the fact and the footer is the prose: a JSON turn and a
         * tool turn have no footer at all, and the headers are on every answer including a refusal.
         * The footer fills in what a header cannot carry, which is the amount.
         */
        @JvmStatic
        @JvmOverloads
        public fun paidBy(answer: SponsoredAnswer?, footer: SponsoredFooter.Sponsor? = null): ShownSponsor? {
            val name = answer?.sponsorName ?: footer?.name ?: return null
            if (name.isEmpty()) return null
            return ShownSponsor(
                id = answer?.sponsorId ?: SponsoredAnswer.sponsorIdFromUrl(answer?.sponsorUrl) ?: footer?.sponsorId,
                name = name,
                url = answer?.sponsorUrl ?: footer?.url,
                handle = answer?.sponsorHandle ?: footer?.handle,
                profileUrl = answer?.sponsorProfileUrl,
                logoUrl = null,
                paid = true,
            )
        }
    }
}

/**
 * THE RULE ABOUT WHAT MAY BE SHOWN WHEN, kept in one object rather than in a timer in a composable.
 *
 * ```
 *  t=0s   the sponsor who PAID for this answer, alone, beaconed once as `line`
 *  t=5s   the rotation may begin: one sponsor from the feed, "also sponsored by Y",
 *         beaconed once as `rotation`, then another every five seconds
 *  background: nothing is shown and nothing is beaconed, because nothing is on screen
 * ```
 *
 * Three things it guarantees, and each of them is why it is not a `LaunchedEffect`:
 *
 *   · **The paid line's first showing is never replaced.** A rotation that started before the person
 *     could read who paid would be selling an impression that was owed to somebody else.
 *   · **One beacon a sponsor a showing.** The dedupe is per ANSWER: a new answer is a new paid line
 *     and a new set, and a recomposition in between is not.
 *   · **A cancelled scope stops everything.** The rotator holds no scope and no timer of its own, so
 *     an app that leaves the screen stops paying for a rotation nobody is looking at. Being stopped
 *     mid-hold means the paid line has NOT had its five seconds, and it gets them again from the
 *     start when the screen comes back, which is the reading the word "visible" asks for.
 */
public class SponsorRotator @JvmOverloads constructor(
    private val impressions: Impressions,
    /** Omit the country and let the edge decide. See [Impressions.rotation]. */
    private val country: String? = null,
    private val limit: Int = Impressions.DEFAULT_LIMIT,
    private val holdMillis: Long = PAID_HOLD_MILLIS,
    private val everyMillis: Long = ROTATE_EVERY_MILLIS,
    private val placement: String? = null,
) {

    private val _current = MutableStateFlow<ShownSponsor?>(null)

    /** The sponsor to draw. Null before the first answer, and while there is nothing to say. */
    public val current: StateFlow<ShownSponsor?> = _current.asStateFlow()

    /** Called for every beacon this rotator records, on the rotator's own coroutine. */
    public var onImpression: ((ImpressionEvent) -> Unit)? = null

    private var job: Job? = null

    /**
     * One answer's showing: who paid for it, which turn it was, and a number that makes it distinct.
     *
     * THE SEQUENCE NUMBER IS LOAD BEARING. `MutableStateFlow` drops a value equal to the one it
     * holds, and two consecutive answers paid for by the SAME sponsor are equal - so without it the
     * second answer would silently reuse the first one's showing, and its line would never be
     * beaconed. A new answer is a new showing even when the sponsor is the same person.
     */
    private data class Showing(val sponsor: ShownSponsor?, val taskId: String?, val seq: Long)

    /**
     * Who paid for the answer that is on screen.
     *
     * A FLOW AND NOT A FIELD, because the running cycle has to react to it: a chat screen starts the
     * rotator once and then has an answer every minute, and a rotator that read the sponsor only at
     * `start` would keep selling the first answer's inventory for the rest of the session.
     */
    private val paidFlow = MutableStateFlow(Showing(null, null, 0))
    private var seq = 0L

    /**
     * `kind:sponsorId` for everything already reported for THIS showing.
     *
     * Cleared by the collector rather than by [show], so that the set and the cycle that reads it are
     * only ever touched on one coroutine.
     */
    private val beaconed = LinkedHashSet<String>()

    /**
     * A new answer: this is who paid for it.
     *
     * Everything resets - the hold, the dedupe, the place in the feed - because this is a different
     * answer and the sponsor who paid for it is owed their own five seconds. Calling it with the same
     * answer twice (a recomposition, a rotation of the screen) resets nothing, because the
     * comparison is on the value.
     */
    @JvmOverloads
    public fun show(sponsor: ShownSponsor?, taskId: String? = null) {
        val held = paidFlow.value
        // THE SAME ANSWER DRAWN AGAIN IS NOT A NEW SHOWING. A recomposition, a rotation of the
        // screen and a re-collection of the same state all land here, and none of them is worth
        // restarting the five seconds or beaconing a second impression for.
        if (held.seq != 0L && sponsor == held.sponsor && taskId == held.taskId) return
        _current.value = sponsor
        seq += 1
        paidFlow.value = Showing(sponsor, taskId, seq)
    }

    /** The same, read off an answer's headers and footer. */
    @JvmOverloads
    public fun show(answer: SponsoredAnswer?, footer: SponsoredFooter.Sponsor? = null, taskId: String? = null) {
        show(ShownSponsor.paidBy(answer, footer), taskId)
    }

    /**
     * Run, in `scope`.
     *
     * `rotate` false is the whole feature turned off: the paid line is still shown and still
     * beaconed, because that is a statement about this answer and not an advertisement.
     */
    @JvmOverloads
    public fun start(scope: CoroutineScope, rotate: Boolean = true) {
        if (job?.isActive == true) return
        impressions.start(scope)
        job = scope.launch {
            // `collectLatest` IS THE RULE. A new answer cancels the cycle mid-delay and starts over
            // with that answer's own paid line, so the five seconds are always the CURRENT sponsor's
            // and a rotation can never outlive the answer it was shown beside.
            paidFlow.collectLatest { showing ->
                beaconed.clear()
                val first = showing.sponsor
                // THE PAID LINE, FIRST AND ALONE.
                if (first != null) {
                    _current.value = first
                    record(ImpressionKind.LINE, first, showing.taskId)
                    delay(holdMillis)
                }
                if (!rotate) return@collectLatest
                val paidId = first?.id
                val feed = impressions.rotation(country, limit)
                    // THE SPONSOR WHO PAID IS NOT ALSO ONE OF THE OTHERS. Showing them twice, the
                    // second time as "also sponsored by", would read as two sponsors where there is
                    // one.
                    .filter { paidId == null || it.id != paidId }
                if (feed.isEmpty()) return@collectLatest
                var index = 0
                // `currentCoroutineContext().isActive` and not `isActive`: inside `collectLatest`
                // the receiver is not a `CoroutineScope`, and this is the loop that a new answer's
                // cancellation has to break out of.
                while (currentCoroutineContext().isActive) {
                    val next = feed[index % feed.size]
                    index++
                    val shown = ShownSponsor.of(next, paid = false)
                    _current.value = shown
                    record(ImpressionKind.ROTATION, shown, showing.taskId)
                    delay(everyMillis)
                }
            }
        }
    }

    /** Stop. What was buffered is flushed by [Impressions.stop]; this only stops the cycling. */
    public fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun record(kind: ImpressionKind, sponsor: ShownSponsor, taskId: String?) {
        // AN ANONYMOUS SPONSOR HAS NO ID AND IS NOT BEACONED. There is nobody to report it to: the
        // line says "an anonymous sponsor" precisely because the row names no one.
        val id = sponsor.id ?: return
        if (!beaconed.add("${kind.wire}:$id")) return
        val event = ImpressionEvent(kind = kind, sponsorId = id, placement = placement, taskId = taskId)
        onImpression?.invoke(event)
        impressions.record(event)
    }

    public companion object {
        /** Five seconds of the paid line before anything else may be shown. */
        public const val PAID_HOLD_MILLIS: Long = 5_000

        /** And five between the ones after it. */
        public const val ROTATE_EVERY_MILLIS: Long = 5_000
    }
}
