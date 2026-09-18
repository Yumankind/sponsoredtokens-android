package com.sponsoredtokens

import java.security.SecureRandom

/**
 * PKCE (RFC 7636), the whole of the authentication at `POST /api/people/connect/token`.
 *
 * That door takes NO credential: a program that ships to other people's machines can hold no
 * secret, so the proof that the app exchanging the code is the app that started the flow is that it
 * can produce the verifier whose sha256 it published as the challenge. Everything here is plain
 * Java, so the shape of a pair is pinned by a unit test rather than by a device.
 */
public data class PkcePair(
    /** 43 characters of base64url. Kept until the redirect comes back, then sent once. */
    public val verifier: String,
    /** `base64url(sha256(verifier))`, 43 characters. What goes in the connect URL. */
    public val challenge: String,
) {
    public val method: String get() = METHOD

    public companion object {
        public const val METHOD: String = "S256"

        /**
         * A fresh pair. 32 bytes of `SecureRandom` is 43 base64url characters, which is the length
         * the site's own page produces and comfortably inside RFC 7636's 43-to-128 range.
         */
        @JvmStatic
        public fun generate(random: SecureRandom = SecureRandom()): PkcePair {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            val verifier = B64.url(bytes)
            return PkcePair(verifier = verifier, challenge = challengeFor(verifier))
        }

        /** `base64url(sha256(verifier))`, over the verifier's ASCII bytes as the RFC says. */
        @JvmStatic
        public fun challengeFor(verifier: String): String = B64.url(sha256(verifier.toByteArray(Charsets.US_ASCII)))

        /** An opaque `state`, the app's own CSRF token. The same 32 bytes, for the same reason. */
        @JvmStatic
        public fun newState(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return B64.url(bytes)
        }
    }
}
