package com.sponsoredtokens.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sponsoredtokens.SponsoredFooter
import com.sponsoredtokens.SponsoredTokens
import com.sponsoredtokens.compose.Allowance
import com.sponsoredtokens.compose.SponsoredBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE WHOLE LIBRARY IN ONE SCREEN: connect, ask, stream, and the sponsor's line under the answer.
 *
 * It is deliberately one file and has no architecture: a sample is read, not extended, and every
 * line that is not about the pool is a line between the reader and the thing they came to see.
 *
 * REPLACE `CLIENT_ID`. It is the `apps` row on sponsoredtokens.com that identifies YOUR app, and the
 * redirect below must be registered on that row exactly as it is written here - the worker matches
 * it as a whole string.
 */
private const val CLIENT_ID = "app_replace_me"
private const val REDIRECT_URI = "com.sponsoredtokens.sample:/oauth2redirect"

class MainActivity : ComponentActivity() {

    private lateinit var sponsored: SponsoredTokens
    private val redirects = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sponsored = SponsoredTokens(
            context = this,
            clientId = CLIENT_ID,
            redirectUri = REDIRECT_URI,
        )
        // The activity may have been STARTED by the redirect rather than resumed into it, so the
        // launch intent is offered as well. `handleRedirect` ignores anything that is not ours.
        redirects.value = intent
        setContent { Screen(sponsored, redirects) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        redirects.value = intent
    }
}

@Composable
private fun Screen(sponsored: SponsoredTokens, redirects: MutableStateFlow<Intent?>) {
    val scope = rememberCoroutineScope()
    val session by sponsored.sessionFlow.collectAsState()
    val answer by sponsored.lastAnswer.collectAsState()

    var prompt by remember { mutableStateOf("Say hello in one sentence.") }
    var body by remember { mutableStateOf("") }
    var footer by remember { mutableStateOf<SponsoredFooter.Sponsor?>(null) }
    var status by remember { mutableStateOf("") }
    val activity = currentActivity()

    val pending by redirects.collectAsState()
    LaunchedEffect(pending) {
        val intent = pending ?: return@LaunchedEffect
        redirects.value = null
        when (val result = sponsored.handleRedirect(intent)) {
            is SponsoredTokens.ConnectResult.Connected ->
                status = "Connected as ${result.session.person?.displayName ?: "this device"}"
            is SponsoredTokens.ConnectResult.Refused -> status = "Refused: ${result.error}"
            is SponsoredTokens.ConnectResult.ToppedUp -> status = if (result.paid) "Payment taken" else "Top-up cancelled"
            SponsoredTokens.ConnectResult.Ignored -> Unit
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("sponsoredtokens", style = MaterialTheme.typography.headlineSmall)

                if (session == null) {
                    Button(onClick = { activity?.let { sponsored.signIn(it) } }) {
                        Text("Connect sponsoredtokens")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    body = ""
                                    footer = null
                                    status = "…"
                                    runCatching { ask(sponsored, prompt) { body = it.body; footer = it.sponsor } }
                                        .onFailure { status = it.message ?: "failed" }
                                        .onSuccess { status = "" }
                                }
                            },
                        ) { Text("Ask") }
                        Button(onClick = { scope.launch { sponsored.signOut() } }) { Text("Sign out") }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Prompt") },
                    )
                }

                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyMedium)

                // THE POINT OF THE SAMPLE. The line names whoever paid for the answer above it, and
                // after five seconds it may begin naming the others who keep the pool full.
                SponsoredBy(
                    answer = answer,
                    footer = footer,
                    impressions = sponsored.impressions,
                    placement = "sample-answer",
                )
                Allowance(answer = answer)
            }
        }
    }
}

/** The activity, for the one call that needs one: opening a Custom Tab. */
@Composable
private fun currentActivity(): android.app.Activity? {
    var context = androidx.compose.ui.platform.LocalContext.current
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * ONE STREAMED ANSWER, with plain OkHttp and no other library.
 *
 * `sponsored.client` already carries the interceptor, so there is no credential in this function and
 * nothing here knows whether the call went out signed or with the bearer. The only thing it does
 * that an ordinary SSE reader does not is feed every delta through [SponsoredFooter.Stream], which
 * is what keeps a half-arrived "— sponsored by [Nor" out of the middle of the prose.
 */
private suspend fun ask(
    sponsored: SponsoredTokens,
    prompt: String,
    onChunk: (SponsoredFooter.Split) -> Unit,
) {
    val model = sponsored.defaultModel()
    val payload = JSONObject()
        .put("model", model)
        .put("stream", true)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
    val request = Request.Builder()
        .url("${sponsored.apiBaseUrl}/chat/completions")
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    withContext(Dispatchers.IO) {
        sponsored.client.newCall(request).execute().use { response ->
            val source = response.body?.source() ?: return@use
            val stream = SponsoredFooter.Stream()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = runCatching {
                    JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content", "").orEmpty()
                }.getOrDefault("")
                if (delta.isEmpty()) continue
                val chunk = stream.push(delta)
                withContext(Dispatchers.Main) { onChunk(SponsoredFooter.Split(chunk.body, null)) }
            }
            val finished = stream.finish()
            withContext(Dispatchers.Main) { onChunk(finished) }
        }
    }
}
