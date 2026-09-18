package com.sponsoredtokens

import org.json.JSONObject

/**
 * A REFUSAL, SAID THE WAY THE POOL SAID IT.
 *
 * Every door on `sponsoredtokens.com` answers in the OpenAI envelope this base URL uses everywhere,
 * `{ "error": { "message", "type", "code", … } }`, and the CODE is the part worth branching on: the
 * message is a sentence for a person and changes, the code is the contract. The ones an Android app
 * meets most are here as constants, each with what to do about it.
 */
public class SponsoredException(
    public val status: Int,
    public val code: String?,
    message: String,
    /** For `app_model_not_allowed`: the ids that would have worked. Empty otherwise. */
    public val allowed: List<String> = emptyList(),
) : RuntimeException(message) {

    /** True when connecting again is the fix: the credential is gone, wrong or expired. */
    public val needsReconnect: Boolean
        get() = status == 401 || code == INVALID_API_KEY || code == UNKNOWN_DEVICE || code == DEVICE_REVOKED

    public companion object {
        /** The credential is not one we recognise. Connect again. */
        public const val INVALID_API_KEY: String = "invalid_api_key"

        /** This device is not registered, or belongs to another app. Connect again. */
        public const val UNKNOWN_DEVICE: String = "unknown_device"

        /** Revoked from the person's own account page. Connect again. */
        public const val DEVICE_REVOKED: String = "device_revoked"

        /** The clock is out by more than five minutes. Nothing a retry fixes; the phone's time is. */
        public const val STALE_SIGNATURE: String = "stale_signature"

        /** A signature sent twice. Sign each request once; never retry a request unchanged. */
        public const val REPLAYED_SIGNATURE: String = "replayed_signature"

        /** A bare model id on a person token. Send the `sponsored/` form the listing gave you. */
        public const val PERSON_TOKEN_POOL_ONLY: String = "person_token_pool_only"

        /** The app's developer did not approve this model. `allowed` names the ones they did. */
        public const val APP_MODEL_NOT_ALLOWED: String = "app_model_not_allowed"

        /** The person is blocked. A ruling, not a shortage: do not retry and do not reconnect. */
        public const val BLOCKED: String = "blocked"

        /** Read a refusal out of a body, whatever shape it turns out to be. */
        internal fun of(status: Int, body: String?): SponsoredException {
            val fallback = "sponsoredtokens refused this request ($status)."
            if (body.isNullOrBlank()) return SponsoredException(status, null, fallback)
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return SponsoredException(status, null, body.take(400))
            // Two envelopes exist on this host: the OpenAI one the `/api/v1` doors answer in, and the
            // flat `{ error, code }` the people routes use. Both are read, because a client that only
            // knows one meets the other on its first bad redirect.
            val error = json.optJSONObject("error")
            val message = error?.optStringOrNull("message")
                ?: json.optStringOrNull("error")
                ?: json.optStringOrNull("message")
                ?: fallback
            val code = error?.optStringOrNull("code") ?: json.optStringOrNull("code")
            val allowed = (error ?: json).optJSONArray("allowed")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it, "").takeIf(String::isNotEmpty) }
            } ?: emptyList()
            return SponsoredException(status, code, message, allowed)
        }
    }
}
