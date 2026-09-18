package com.sponsoredtokens

import org.json.JSONArray
import org.json.JSONObject

/**
 * WHAT THE PEOPLE SURFACE ANSWERS, as Kotlin.
 *
 * The shapes are `docs/sponsoredtokens/HANDOFF-people.md` §2 and §4. Every field a client is
 * documented to read is here and nothing is defaulted: a figure that was not sent is null, because
 * the whole surface is written so that absent and zero are different answers.
 *
 * `org.json` is the parser because it is IN THE PLATFORM - no dependency in the AAR, no reflection,
 * no code generation and nothing for an app's shrinker to keep. In a JVM unit test `android.jar`
 * ships it as a stub that throws, so the build puts the real `org.json:json` on the test classpath
 * ahead of it; that is the one line of test configuration this choice costs.
 */

/** The person a token belongs to. `displayName` is NULL for a passkey account, which has no name. */
public data class Person(
    public val id: String,
    public val displayName: String?,
    public val blocked: Boolean = false,
) {
    internal companion object {
        fun of(json: JSONObject?): Person? {
            val id = json?.optStringOrNull("id") ?: return null
            return Person(id, json.optStringOrNull("displayName"), json.optBoolean("blocked", false))
        }
    }
}

/**
 * The device the connect flow minted for this app's keypair.
 *
 * Its presence is the whole of the decision in [AuthMode.AUTO]: a device means the four signing
 * headers are available, and they need no stored secret at all.
 */
public data class SigningDevice(
    public val id: String,
    public val appId: String,
) {
    internal companion object {
        fun of(json: JSONObject?): SigningDevice? {
            val id = json?.optStringOrNull("id") ?: return null
            val appId = json.optStringOrNull("appId") ?: return null
            return SigningDevice(id, appId)
        }
    }
}

/** The person's own weekly allowance on the pool. The pool's rules, and no constant of ours. */
public data class Allowance(
    public val unit: String?,
    public val budgetCents: Int?,
    public val usedCents: Int?,
    public val remainingCents: Int?,
    public val week: String?,
    public val weekStart: String?,
    public val resetsAt: String?,
    public val day: String?,
) {
    internal companion object {
        fun of(json: JSONObject?): Allowance? {
            if (json == null) return null
            return Allowance(
                unit = json.optStringOrNull("unit"),
                budgetCents = json.optIntOrNull("budgetCents"),
                usedCents = json.optIntOrNull("usedCents"),
                remainingCents = json.optIntOrNull("remainingCents"),
                week = json.optStringOrNull("week"),
                weekStart = json.optStringOrNull("weekStart"),
                resetsAt = json.optStringOrNull("resetsAt"),
                day = json.optStringOrNull("day"),
            )
        }
    }
}

/** A sponsor, as `/api/people/me` names the last one this person's turns drew. */
public data class LastSponsor(public val name: String, public val url: String?)

/** What the app may spend of the person's own money, and whether it may at all (§5a step c). */
public data class PaidBalance(
    public val allowed: Boolean,
    /** NULL unless `allowed` - the balance is not read at all for an app that has not opted in. */
    public val balanceCents: Int?,
)

/** The app this credential is bound to, as the person's own `/me` reports it. */
public data class ConnectedApp(
    public val id: String,
    public val name: String?,
    /** True when the app has an allowance with something left in it: step (b) of the ladder. */
    public val paysExtras: Boolean,
)

/** `GET /api/people/me`. */
public data class Me(
    public val person: Person?,
    public val allowance: Allowance?,
    public val lastSponsor: LastSponsor?,
    public val poolRemainingCents: Int?,
    public val paid: PaidBalance?,
    public val app: ConnectedApp?,
    public val deviceExpiresAt: String?,
) {
    internal companion object {
        fun of(json: JSONObject): Me = Me(
            person = Person.of(json.optJSONObject("person")),
            allowance = Allowance.of(json.optJSONObject("allowance")),
            lastSponsor = json.optJSONObject("lastSponsor")?.let { sponsor ->
                sponsor.optStringOrNull("name")?.let { LastSponsor(it, sponsor.optStringOrNull("url")) }
            },
            poolRemainingCents = json.optJSONObject("pool")?.optIntOrNull("remainingCents"),
            paid = json.optJSONObject("paid")?.let {
                PaidBalance(it.optBoolean("allowed", false), it.optIntOrNull("balanceCents"))
            },
            app = json.optJSONObject("app")?.let { app ->
                app.optStringOrNull("id")?.let {
                    ConnectedApp(it, app.optStringOrNull("name"), app.optBoolean("paysExtras", false))
                }
            },
            deviceExpiresAt = json.optJSONObject("device")?.optStringOrNull("expiresAt"),
        )
    }
}

/**
 * One row of `GET /api/v1/models`.
 *
 * SEND `id` EXACTLY AS IT IS LISTED. The `sponsored/` prefix says who pays, and a person token that
 * sends the bare form is refused with `403 person_token_pool_only`. A client that picks from this
 * list never meets that refusal, which is the promise the listing makes.
 */
public data class SponsoredModel(
    public val id: String,
    /** True for the `sponsored/auto` row: ask for it and the app chooses by its own rule. */
    public val auto: Boolean,
    public val name: String?,
    /** The bare upstream id behind a `sponsored/` one. */
    public val source: String?,
    public val tier: Int?,
    /** The app's model policy flags, where the app has one: which purse may pay for this model. */
    public val pool: Boolean?,
    public val app: Boolean?,
)

/** The shelf this credential may spend, and the two figures the envelope carries with it. */
public data class ModelShelf(
    public val models: List<SponsoredModel>,
    public val tier: Int?,
    public val weeklyRemainingCents: Int?,
) {
    /**
     * What to send when the app has no opinion.
     *
     * `sponsored/auto` when the list offers it, because "the app chooses" is a better default than
     * this library choosing; otherwise the first row, which is the dearest model the credential's
     * tier unlocks, in the order the pool listed it.
     */
    public fun defaultModel(): String? = models.firstOrNull { it.auto }?.id ?: models.firstOrNull()?.id

    internal companion object {
        fun of(json: JSONObject): ModelShelf {
            val rows = json.optJSONArray("data") ?: JSONArray()
            val models = ArrayList<SponsoredModel>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optStringOrNull("id") ?: continue
                models.add(
                    SponsoredModel(
                        id = id,
                        auto = row.optBoolean("auto", false),
                        name = row.optStringOrNull("name"),
                        source = row.optStringOrNull("sponsored_source"),
                        tier = row.optIntOrNull("tier"),
                        pool = row.optBooleanOrNull("pool"),
                        app = row.optBooleanOrNull("app"),
                    ),
                )
            }
            return ModelShelf(models, json.optIntOrNull("tier"), json.optIntOrNull("weeklyRemainingCents"))
        }
    }
}

/**
 * A connected session: the person token, when it dies, and the device that can sign instead of it.
 *
 * Stored as one JSON object, so a field added to the contract later is a field an older build reads
 * past rather than a store it cannot open.
 */
public data class Session(
    public val personToken: String,
    public val expiresAt: String?,
    public val person: Person?,
    public val allowance: Allowance?,
    public val signingDevice: SigningDevice?,
) {
    internal fun toJson(): String = JSONObject().apply {
        put("personToken", personToken)
        expiresAt?.let { put("expiresAt", it) }
        person?.let { put("person", JSONObject().put("id", it.id).putOpt("displayName", it.displayName)) }
        signingDevice?.let { put("signingDevice", JSONObject().put("id", it.id).put("appId", it.appId)) }
    }.toString()

    internal companion object {
        /** From the token exchange's answer, or from the store. One reader, so they cannot diverge. */
        fun of(json: JSONObject): Session? {
            val token = json.optStringOrNull("personToken") ?: return null
            return Session(
                personToken = token,
                expiresAt = json.optStringOrNull("expiresAt"),
                person = Person.of(json.optJSONObject("person")),
                allowance = Allowance.of(json.optJSONObject("allowance")),
                signingDevice = SigningDevice.of(json.optJSONObject("signingDevice")),
            )
        }

        fun read(stored: String?): Session? = stored?.let { runCatching { of(JSONObject(it)) }.getOrNull() }
    }
}

// ── `org.json`'s own nulls ──────────────────────────────────────────────────────────────────────
//
// `optString` answers `""` for a missing key AND for a JSON `null`, and `optInt` answers `0`, which
// is exactly the confusion between absent and empty this surface is written to avoid. These four
// say null when the key is not a value.

internal fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name, "").takeIf { it.isNotEmpty() }
}

internal fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    val value = opt(name)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

internal fun JSONObject.optBooleanOrNull(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
}
