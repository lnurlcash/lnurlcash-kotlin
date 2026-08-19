package io.github.thecryptodonkey.lnurlcash

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The conformance repo's mock mint: a real HTTP server that can be told to
 * misbehave. Running the real server rather than stubbing the HTTP client is
 * deliberate - a stub only ever fails in ways this library already anticipates,
 * and the interesting failures are the ones it does not.
 */
internal class MockMint private constructor(
    val url: String,
    val pubkey: String,
    private val process: Process,
) : AutoCloseable {

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun hook(path: String): Map<String, String?> {
        val request = HttpRequest.newBuilder().uri(URI.create("$url$path")).GET().build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return json.parseToJsonElement(body).jsonObject.mapValues { (_, value) ->
            value.toString().removeSurrounding("\"").takeUnless { it == "null" }
        }
    }

    /** Bring a note into existence. Returns the signature the mint issued. */
    fun credit(k1: String, amountMsat: Long): String? {
        val body = hook("/_test/credit?k1=$k1&amount=$amountMsat")
        check(body["status"] == "OK") { "credit failed: $body" }
        return body["sig"]
    }

    /**
     * What the SERVICE thinks of a note: outstanding, pending or burned.
     * Asserting on this rather than on the service's replies is the point - it
     * is the difference between what a mint says and what it did.
     */
    fun noteState(k1: String): String? = hook("/_test/state?k1=$k1")["state"]

    /** Mark an invoice paid. The mock invents its invoices; nothing can pay one. */
    fun settle(paymentHash: String) {
        val body = hook("/_test/settle?payment_hash=$paymentHash")
        check(body["status"] == "OK") { "settle failed: $body" }
    }

    fun noteUrl(k1: String, amountMsat: Long? = null): String =
        if (amountMsat == null) "$url/w?k1=$k1" else "$url/w?k1=$k1&amount=$amountMsat"

    fun callback(): String = "$url/w/cb"

    override fun close() {
        process.destroy()
        process.waitFor()
    }

    companion object {
        fun start(vararg flags: String): MockMint? {
            val script = Vectors.directory.parent.resolve("mock-mint").resolve("index.mjs")
            if (!Files.exists(script)) return null
            val command = mutableListOf("node", script.toString(), "--port=0", "--testHooks=true")
            command.addAll(flags)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var url: String? = null
            var pubkey: String? = null
            while (url == null || pubkey == null) {
                val line = reader.readLine() ?: break
                Regex("listening on (\\S+)").find(line)?.let { url = it.groupValues[1] }
                Regex("mint pubkey:\\s+(\\S+)").find(line)?.let { pubkey = it.groupValues[1] }
            }
            val resolvedUrl = url
            val resolvedPubkey = pubkey
            if (resolvedUrl == null || resolvedPubkey == null) {
                process.destroy()
                error("the mock mint did not start")
            }
            return MockMint(resolvedUrl, resolvedPubkey, process)
        }
    }
}

/** A deterministic 32-byte secret, so a failing test names the same note twice. */
internal fun secret(seed: Int): String = "%02x".format(seed).repeat(32)
