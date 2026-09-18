package com.sponsoredtokens

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * THE ONE PLACE A CALL TO THE POOL IS DRESSED, in both directions.
 *
 * Going out it puts on either the bearer or the four `X-Sponsoredtokens-*` headers; coming back it
 * reads the ten `x-sponsored-*` ones off the answer and hands them to whoever is watching. Every
 * request the app makes to the pool goes through it, whether the app writes its own OkHttp calls or
 * hands the client to some other library, so there is no second place where a credential could be
 * attached differently.
 *
 * **It never sends both.** A bearer beside a signature would be a credential travelling for no
 * reason on a road that was chosen precisely so that nothing has to be stored, so a signed call
 * REMOVES any `Authorization` header the caller set.
 *
 * **It cannot sign a body it cannot read twice.** A one-shot or duplex `RequestBody` is a stream,
 * and a stream cannot be hashed and then also sent. Rather than sign the empty string over content
 * the server will actually see - a signature over something that was never sent - such a body is
 * refused by name.
 */
public class SponsoredInterceptor(
    /** True for the URLs this interceptor is allowed to touch. Everything else passes untouched. */
    private val appliesTo: (HttpUrl) -> Boolean,
    /** The credential headers for one request, given what will be signed. */
    private val headersFor: (method: String, url: HttpUrl, body: ByteArray) -> Map<String, String>,
    /** Called once per answer that carried any `x-sponsored-*` header, on the calling thread. */
    private val onAnswer: (SponsoredAnswer) -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!appliesTo(request.url)) return chain.proceed(request)

        val method = request.method.uppercase()
        // THE WORKER HASHES THE EMPTY STRING FOR A GET OR A HEAD whatever the request carries
        // (`routes/sponsored-proxy.ts`), so this does too, and a body on one of those is not signed.
        val body = if (method == "GET" || method == "HEAD") ByteArray(0) else bodyBytes(request)

        val builder = request.newBuilder()
        val credential = headersFor(method, request.url, body)
        if (credential.keys.any { it.equals("Authorization", ignoreCase = true) }) {
            builder.removeHeader(Signing.HEADER_APP)
            builder.removeHeader(Signing.HEADER_DEVICE)
            builder.removeHeader(Signing.HEADER_TIMESTAMP)
            builder.removeHeader(Signing.HEADER_SIGNATURE)
        } else if (credential.isNotEmpty()) {
            builder.removeHeader("Authorization")
        }
        for ((name, value) in credential) builder.header(name, value)

        val response = chain.proceed(builder.build())
        SponsoredAnswer.from(response.headers)?.let(onAnswer)
        return response
    }

    private fun bodyBytes(request: okhttp3.Request): ByteArray {
        val body = request.body ?: return ByteArray(0)
        if (body.isDuplex() || body.isOneShot()) {
            throw IOException(
                "A device-signed request cannot carry a streamed body: the signature covers " +
                    "sha256(body), and a one-shot body cannot be both hashed and sent. Buffer it, " +
                    "or use the bearer.",
            )
        }
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readByteArray()
    }
}
