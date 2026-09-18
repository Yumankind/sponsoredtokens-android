package com.sponsoredtokens.integrations

import com.sponsoredtokens.SponsoredTokens
import dev.langchain4j.model.openai.OpenAiChatModel

/**
 * LANGCHAIN4J, ON THE POOL - and one honest limitation stated up front.
 *
 * ```kotlin
 * val model = sponsored.langchain4jModel(sponsored.defaultModel())
 * val answer = model.chat("Say hello")
 * ```
 *
 * ── WHAT THIS USES, AND WHAT IT CANNOT ──────────────────────────────────────────────────────────
 *
 * `OpenAiChatModel` takes a fixed map of custom headers, which is exactly enough for the BEARER: the
 * person token is one constant string for thirty days, so a map taken once stays correct.
 *
 * IT IS NOT ENOUGH FOR A SIGNED CALL. A device signature covers the method, the path, the query, the
 * minute and the sha256 of the body, so it is a different string for every request; a header map
 * handed over once would stop verifying within five minutes and would never have covered the body at
 * all. LangChain4j's OpenAI module builds its own HTTP client and exposes no per-request hook this
 * library can reach, so this adapter uses the bearer and says so rather than shipping something that
 * appears to sign and does not. For signed calls use [SponsoredTokens.client], which is an
 * `OkHttpClient` with the interceptor already on it.
 *
 * ── AND ONE THING TO KNOW BEFORE YOU REACH FOR IT ───────────────────────────────────────────────
 *
 * `langchain4j-open-ai` is a JVM library whose default transport is `java.net.http.HttpClient`,
 * which DOES NOT EXIST ON ANDROID. On a phone it needs an HTTP client of its own that runs there, or
 * it will fail at the first call with a `NoClassDefFoundError` that has nothing to do with us. It
 * works unchanged in a JVM module of an Android project - a server, a test, a desktop tool sharing
 * this code. That is a fact about LangChain4j and not about the pool, and it is written here because
 * the cheapest place to find it out is before you write the call.
 */
public fun SponsoredTokens.langchain4jModel(model: String): OpenAiChatModel {
    val bearer = bearerHeaders()
    check(bearer.isNotEmpty()) {
        "This app is not connected, so there is no person token to give LangChain4j. Call signIn() first."
    }
    return OpenAiChatModel.builder()
        .baseUrl(apiBaseUrl)
        // THE KEY IS NOT THE CREDENTIAL HERE. The pool reads `Authorization` and this string is only
        // what stops the client library from refusing to start without one; it is never sent as an
        // API key, because `customHeaders` sets `Authorization` over the top of it.
        .apiKey("sponsored")
        .modelName(model)
        .customHeaders(bearer)
        .build()
}
