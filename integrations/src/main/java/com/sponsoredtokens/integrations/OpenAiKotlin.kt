package com.sponsoredtokens.integrations

import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.sponsoredtokens.SponsoredTokens
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header

/**
 * THE COMMUNITY OPENAI KOTLIN CLIENT (`com.aallam.openai`), ON THE POOL.
 *
 * ```kotlin
 * val openAI = OpenAI(sponsored.openAiConfig())
 * ```
 *
 * The same limitation as the LangChain4j adapter and for the same reason: the hook this client
 * offers is a Ktor `HttpClientConfig`, where a plugin can add a header to every request but cannot
 * be handed the serialized body in time to hash it. So the plugin carries the BEARER, which is one
 * constant string and is correct on every request. For signed calls use [SponsoredTokens.client].
 *
 * The bearer is read PER REQUEST rather than captured once, so signing out, reconnecting, or a token
 * that was refreshed while the app was running all take effect on the next call rather than on the
 * next time somebody builds a client.
 */
public fun SponsoredTokens.openAiConfig(): OpenAIConfig = OpenAIConfig(
    host = OpenAIHost(baseUrl = "$apiBaseUrl/"),
    // Not the credential; the plugin below sets `Authorization`. This is what stops the client from
    // refusing to start without a token at all.
    token = "sponsored",
    httpClientConfig = { installSponsoredTokens(this@openAiConfig) },
)

/** The plugin on its own, for an app that builds its own Ktor client. */
public fun HttpClientConfig<*>.installSponsoredTokens(sponsored: SponsoredTokens) {
    install(
        createClientPlugin("SponsoredTokens") {
            onRequest { request, _ ->
                for ((name, value) in sponsored.bearerHeaders()) request.header(name, value)
            }
        },
    )
}
