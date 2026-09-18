package com.sponsoredtokens.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import com.sponsoredtokens.ImpressionEvent
import com.sponsoredtokens.Impressions
import com.sponsoredtokens.PaidBy
import com.sponsoredtokens.ShownSponsor
import com.sponsoredtokens.SponsoredAnswer
import com.sponsoredtokens.SponsoredFooter
import com.sponsoredtokens.SponsorRotator

/**
 * THE LINE THAT SAYS WHO PAID, and after five seconds, who else is paying for the pool.
 *
 * ```
 *   sponsored by Acme · X @acme · $0.42
 *   also sponsored by Northwind Labs
 * ```
 *
 * The name and the handle are links, opened in the browser rather than in the app: the sponsor's own
 * site is the sponsor's, and a person who taps it should arrive there with an address bar, not in a
 * frame this app drew. The two destinations are the worker's own
 * (`x-sponsored-sponsor-url` for the name, `x-sponsored-sponsor-profile` for the handle) and are
 * never guessed from the other.
 *
 * **What is drawn when.** The sponsor who PAID for this answer is shown first and alone. Only after
 * that line has been visible for five seconds may the rotation begin, and only when `rotate` is on
 * and an [Impressions] was given. Pass `impressions = null` and this is a plain, honest credit line
 * with no beacons and no cycling, which is what an app that has not sold any inventory wants.
 *
 * **It pauses with the app.** The cycle runs inside `repeatOnLifecycle(RESUMED)`, so a backgrounded
 * app shows nobody and reports nobody. Whatever was buffered is flushed on the way out, because an
 * app that has been stopped may not be given another chance to run.
 */
@Composable
public fun SponsoredBy(
    answer: SponsoredAnswer?,
    modifier: Modifier = Modifier,
    /** The footer of the same answer, where the app split one. It carries the amount; headers do not. */
    footer: SponsoredFooter.Sponsor? = null,
    /** Give it one and the line becomes inventory. Leave it null and it is only a credit. */
    impressions: Impressions? = null,
    rotate: Boolean = true,
    taskId: String? = null,
    placement: String? = null,
    onImpression: ((ImpressionEvent) -> Unit)? = null,
) {
    val paid = remember(answer, footer) { ShownSponsor.paidBy(answer, footer) }

    if (impressions == null) {
        SponsorLine(paid, modifier)
        return
    }

    val rotator = remember(impressions, placement) { SponsorRotator(impressions, placement = placement) }
    DisposableEffect(rotator, onImpression) {
        rotator.onImpression = onImpression
        onDispose { rotator.onImpression = null }
    }
    LaunchedEffect(rotator, paid, taskId) { rotator.show(paid, taskId) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(rotator, lifecycleOwner, rotate) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            rotator.start(this, rotate = rotate)
            try {
                // Hold this block open for as long as the app is resumed. `repeatOnLifecycle`
                // cancels it on the way to the background, and that cancellation is what stops the
                // cycle: nothing is shown and nothing is reported while nobody is looking.
                awaitCancellation()
            } finally {
                rotator.stop()
                // ON THE WAY OUT, EVERYTHING BUFFERED GOES - under `NonCancellable`, because this
                // runs precisely when the scope has just been cancelled and an ordinary suspend call
                // would be refused before it sent anything. A stopped process may never run again.
                withContext(NonCancellable) { impressions.flush() }
            }
        }
    }

    val shown by rotator.current.collectAsState()
    SponsorLine(shown ?: paid, modifier)
}

/** The line itself, with no timing and no beacons in it. Drawn by both roads above. */
@Composable
private fun SponsorLine(sponsor: ShownSponsor?, modifier: Modifier = Modifier) {
    if (sponsor == null) return
    val uris = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${sponsor.lead} ",
            style = MaterialTheme.typography.labelMedium,
            color = muted,
        )
        Text(
            text = sponsor.name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (sponsor.url != null) MaterialTheme.colorScheme.primary else muted,
            textDecoration = if (sponsor.url != null) TextDecoration.Underline else null,
            modifier = if (sponsor.url != null) {
                Modifier.clickable { runCatching { uris.openUri(sponsor.url!!) } }
            } else {
                Modifier
            },
        )
        sponsor.handle?.let { handle ->
            Text(" · ", style = MaterialTheme.typography.labelMedium, color = muted)
            Text(
                text = handle,
                style = MaterialTheme.typography.labelMedium,
                color = if (sponsor.profileUrl != null) MaterialTheme.colorScheme.primary else muted,
                textDecoration = if (sponsor.profileUrl != null) TextDecoration.Underline else null,
                modifier = if (sponsor.profileUrl != null) {
                    Modifier.clickable { runCatching { uris.openUri(sponsor.profileUrl!!) } }
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * "$3.63 of $5.00 left this week", and nothing when there is nothing to say.
 *
 * ABSENT IS NOT ZERO. An answer that carried no allowance headers draws no bar and no figure, rather
 * than a bar at zero, because "we were not told" and "you have none left" are two very different
 * things to show somebody about their own money. The same applies to the paid balance, which is only
 * ever sent where the app is allowed to spend it.
 */
@Composable
public fun Allowance(
    answer: SponsoredAnswer?,
    modifier: Modifier = Modifier,
) {
    val remaining = answer?.remainingCents ?: return
    val budget = answer.budgetCents
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildString {
        append(dollars(remaining))
        if (budget != null) append(" of ").append(dollars(budget))
        append(" left this week")
        // The purse that actually paid, when it was not the pool: a person whose week is spent and
        // whose app is paying should not be left reading a figure that did not move.
        when (answer.paidBy) {
            PaidBy.APP -> append(" · this answer was on the app")
            PaidBy.PERSON -> append(" · this answer was on your own balance")
            else -> Unit
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = muted,
        modifier = modifier.padding(vertical = 2.dp),
    )
}

/** Whole cents as dollars. The wire is always an integer number of cents and never a fraction. */
private fun dollars(cents: Int): String = "\$%d.%02d".format(cents / 100, (cents % 100 + 100) % 100)
