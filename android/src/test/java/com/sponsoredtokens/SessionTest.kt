package com.sponsoredtokens

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the token exchange answers, what gets stored, and what comes back out of the store. */
class SessionTest {

    private val exchanged = """
        {
          "personToken": "sk-st-p-abcdefghijkl.MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIz",
          "expiresAt": "2026-10-18T12:00:00.000Z",
          "person": { "id": "u_1", "displayName": "alice" },
          "allowance": { "unit": "usd_cents", "budgetCents": 500, "usedCents": 137, "remainingCents": 363 },
          "signingDevice": { "id": "dev_9q2m", "appId": "app_00mini" }
        }
    """.trimIndent()

    @Test
    fun `the exchange's answer becomes a session`() {
        val session = Session.of(JSONObject(exchanged))!!
        assertTrue(session.personToken.startsWith("sk-st-p-"))
        assertEquals("2026-10-18T12:00:00.000Z", session.expiresAt)
        assertEquals("alice", session.person?.displayName)
        assertEquals(SigningDevice("dev_9q2m", "app_00mini"), session.signingDevice)
        assertEquals(363, session.allowance?.remainingCents)
    }

    @Test
    fun `a passkey account has no display name, and none is invented`() {
        val session = Session.of(JSONObject("""{ "personToken": "sk-st-p-a.b", "person": { "id": "u_2", "displayName": null } }"""))!!
        assertEquals("u_2", session.person?.id)
        assertNull(session.person?.displayName)
    }

    @Test
    fun `a session round trips through the store`() {
        val session = Session.of(JSONObject(exchanged))!!
        val back = Session.read(session.toJson())!!
        assertEquals(session.personToken, back.personToken)
        assertEquals(session.expiresAt, back.expiresAt)
        assertEquals(session.person, back.person)
        assertEquals(session.signingDevice, back.signingDevice)
    }

    @Test
    fun `an unreadable store is no session rather than a crash`() {
        assertNull(Session.read("not json"))
        assertNull(Session.read(null))
        assertNull(Session.read("{}"))
    }

    @Test
    fun `an answer with no signing device falls back to the bearer`() {
        val session = Session.of(JSONObject("""{ "personToken": "sk-st-p-a.b" }"""))!!
        assertNull(session.signingDevice)
    }

    @Test
    fun `the shelf prefers auto, which means the app chooses`() {
        val shelf = ModelShelf.of(
            JSONObject(
                """
                { "object": "list", "tier": 0, "weeklyRemainingCents": 500, "data": [
                  { "id": "sponsored/auto", "object": "model", "auto": true, "name": "Auto (the app chooses)" },
                  { "id": "sponsored/anthropic/claude-haiku-4.5", "sponsored_source": "anthropic/claude-haiku-4.5", "tier": 0, "pool": true, "app": false }
                ] }
                """.trimIndent(),
            ),
        )
        assertEquals("sponsored/auto", shelf.defaultModel())
        assertEquals(0, shelf.tier)
        assertEquals(500, shelf.weeklyRemainingCents)
        assertEquals("anthropic/claude-haiku-4.5", shelf.models[1].source)
        assertEquals(true, shelf.models[1].pool)
        assertEquals(false, shelf.models[1].app)
    }

    @Test
    fun `without an auto row the first listed model is the default`() {
        val shelf = ModelShelf.of(
            JSONObject("""{ "data": [ { "id": "sponsored/anthropic/claude-sonnet-5" }, { "id": "sponsored/openai/gpt-5" } ] }"""),
        )
        assertEquals("sponsored/anthropic/claude-sonnet-5", shelf.defaultModel())
        // A policy flag that was not sent is not a flag that is false.
        assertNull(shelf.models[0].pool)
    }

    @Test
    fun `a refusal keeps its code and the ids that would have worked`() {
        val refusal = SponsoredException.of(
            403,
            """{"error":{"message":"…","type":"invalid_request_error","code":"app_model_not_allowed","allowed":["anthropic/claude-haiku-4.5"]}}""",
        )
        assertEquals(SponsoredException.APP_MODEL_NOT_ALLOWED, refusal.code)
        assertEquals(listOf("anthropic/claude-haiku-4.5"), refusal.allowed)
        assertEquals(false, refusal.needsReconnect)
    }

    @Test
    fun `the people surface's flatter refusal reads too`() {
        val refusal = SponsoredException.of(400, """{"error":"bad code","code":"invalid_grant"}""")
        assertEquals("invalid_grant", refusal.code)
        assertEquals("bad code", refusal.message)
    }

    @Test
    fun `a 401 is a reconnect whatever it says`() {
        assertTrue(SponsoredException.of(401, """{"error":{"code":"invalid_api_key"}}""").needsReconnect)
        assertTrue(SponsoredException.of(401, null).needsReconnect)
        // A blocked person is a RULING and not a shortage: reconnecting would be a loop they cannot win.
        assertEquals(false, SponsoredException.of(403, """{"error":{"code":"blocked"}}""").needsReconnect)
    }
}
