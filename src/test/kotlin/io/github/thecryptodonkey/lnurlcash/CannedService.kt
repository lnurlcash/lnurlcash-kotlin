package io.github.thecryptodonkey.lnurlcash

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * A loopback HTTP service that answers each request with the next of
 * [answers] (the last one repeats) and records what it was asked.
 *
 * The mock mint cannot say everything a test here needs a mint to say: a
 * Part 2 answer, a bare OK to a key, a connection dropped after the request
 * arrived. This can, and the requests still go out through the library's own
 * OkHttp client exactly as they would to a real mint.
 */
internal class CannedService(vararg answers: Answer) : AutoCloseable {

    /** Every request answered with [body], with a 200. */
    constructor(body: String) : this(Answer.Json(body))

    sealed interface Answer {
        /** A status and a body. */
        data class Json(val body: String, val status: Int = 200) : Answer

        /** The request arrives, and the connection closes with nothing on it. */
        data object Drop : Answer

        /** The request arrives, and nothing comes back until the service is closed. */
        data object Stall : Answer
    }

    private val script: List<Answer> = answers.toList().also { require(it.isNotEmpty()) }
    private val released = CountDownLatch(1)
    private val threads = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    val requests: MutableList<URI> = CopyOnWriteArrayList()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/") { exchange ->
            requests.add(exchange.requestURI)
            when (val answer = script[minOf(requests.size, script.size) - 1]) {
                is Answer.Json -> {
                    val bytes = answer.body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(answer.status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                // with no response headers sent, closing the exchange closes
                // the connection itself: the client sees the answer lost
                Answer.Drop -> exchange.close()
                Answer.Stall -> {
                    released.await(10, TimeUnit.SECONDS)
                    exchange.close()
                }
            }
        }
        // one thread per exchange, so a stalled answer cannot hold up another
        executor = threads
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** Every value of [key] on the one request this service received. */
    fun params(key: String): List<String> {
        assertEquals(1, requests.size, "exactly one request")
        return requests.single().toString().params(key)
    }

    fun param(key: String): String? = params(key).firstOrNull()

    override fun close() {
        released.countDown()
        server.stop(0)
        threads.shutdownNow()
    }
}

internal fun String.params(key: String): List<String> =
    (URI(this).rawQuery ?: "").split('&')
        .filter { it.isNotEmpty() }
        .map { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
        .filter { it.first == key }
        .map { it.second }

internal fun String.param(key: String): String? = params(key).firstOrNull()
