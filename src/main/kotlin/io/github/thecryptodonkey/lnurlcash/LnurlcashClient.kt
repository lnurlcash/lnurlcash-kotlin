package io.github.thecryptodonkey.lnurlcash

import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uniffi.lnurlcash_core.FfiRequest
import uniffi.lnurlcash_core.LnurlcashException
import uniffi.lnurlcash_core.invoiceRequest
import uniffi.lnurlcash_core.meltRequest
import uniffi.lnurlcash_core.mergeRequest
import uniffi.lnurlcash_core.mintAddressRequest
import uniffi.lnurlcash_core.noteInfoRequest
import uniffi.lnurlcash_core.parseInvoice
import uniffi.lnurlcash_core.parseMintAddress
import uniffi.lnurlcash_core.parseMutation
import uniffi.lnurlcash_core.parseNoteInfo
import uniffi.lnurlcash_core.parsePayRequest
import uniffi.lnurlcash_core.parseVerify
import uniffi.lnurlcash_core.payRequestRequest
import uniffi.lnurlcash_core.rotateRequest
import uniffi.lnurlcash_core.splitRequest
import uniffi.lnurlcash_core.verifyRequest

/**
 * An LNURLcash client.
 *
 * Everything about the protocol - what to request, what a response means, which
 * secrets must survive a failure - lives in the Rust core. This class performs
 * the GET and shapes the result into Kotlin types.
 *
 * Mutating operations return [MutationOutcome] rather than throwing. That is
 * the point of this wrapper: the dangerous case is not an error but an
 * *unknown*, and a `catch` block invites treating "the answer was lost" as "it
 * did not happen".
 *
 * @param http the HTTP client to use. Supply your own to configure proxies,
 *   Tor, or connection pooling - but see the note on retries below, which is
 *   not optional.
 * @param timeout bounded wait. Without one a hung service blocks forever.
 * @param offline refuse to make any request at all. For a caller that wants
 *   certainty nothing reaches the network, rather than trusting that it
 *   happens not to.
 * @param secretSource where replacement note secrets come from. Substitute for
 *   a hardware RNG - and note that a predictable secret is a note anyone can
 *   spend.
 */
public class LnurlcashClient(
    private val timeout: Duration = Duration.ofSeconds(30),
    private val http: OkHttpClient = defaultHttpClient(timeout),
    private val offline: Boolean = false,
    private val secretSource: () -> String = ::generateNoteSecret,
) {

    public companion object {
        /**
         * An HTTP client that will not retry.
         *
         * This is the single most important line in this file, and it is worth
         * saying why in full.
         *
         * Every LNURLcash mutation - rotate, split, merge, melt - is an HTTP
         * GET. HTTP considers GET idempotent, so clients retry one freely when
         * a connection fails mid-flight. An LNURLcash mutation is emphatically
         * not idempotent: the first attempt burns the input note.
         *
         * So when a service applies a rotate and the connection then drops, a
         * retrying client sends it again, gets "invalid or already spent k1"
         * for the second attempt, and reports a definitive rejection. The
         * caller concludes nothing happened and discards the fresh secret -
         * which was the only copy of the note the service just minted. The
         * money is gone, and every layer behaved reasonably.
         *
         * The JDK's own `java.net.http.HttpClient` does exactly this and
         * offers no way to switch it off, which is why this library depends on
         * OkHttp rather than using what is already in the JDK. If you supply
         * your own client, it MUST have retries disabled.
         */
        public fun defaultHttpClient(timeout: Duration): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build()
    }

    /**
     * The informational GET. Never burns, rotates or alters the note.
     *
     * This necessarily puts the secret on the wire, so a caller still holding
     * the note afterwards should [rotate] it.
     */
    public suspend fun fetchNoteInfo(url: String): NoteInfo {
        val body = get(noteInfoRequest(url))
        val info = parseNoteInfo(body, url)
        return NoteInfo(
            callback = info.callback,
            k1 = info.k1,
            maxWithdrawableMsat = info.maxWithdrawable.toLong(),
            minWithdrawableMsat = info.minWithdrawable.toLong(),
            defaultDescription = info.defaultDescription,
            mintPubkey = info.mintPubkey,
        )
    }

    /**
     * Burn [k1] and receive a fresh secret of the same value, which the service
     * never sees: this wallet generates it and discloses only its hash.
     *
     * Closes the window in which any previous holder - or a logged URL, or the
     * service that generated the original preimage - could still redeem the note.
     */
    public suspend fun rotate(callback: String, k1: String): MutationOutcome<RotatedNote> {
        val fresh = secretSource()
        return mutate({ rotateRequest(callback, k1, fresh) }) { response ->
            RotatedNote(k1 = fresh, signature = response.signature)
        }
    }

    /**
     * Burn one or many notes; mint one worth [amountMsat] and one carrying the
     * remainder. Splitting several at once needs no prior merge.
     */
    public suspend fun split(
        callback: String,
        k1s: List<String>,
        amountMsat: Long,
    ): MutationOutcome<SplitNotes> {
        val fresh = secretSource()
        val change = secretSource()
        return mutate(
            { splitRequest(callback, k1s, amountMsat.toULong(), fresh, change) },
        ) { response ->
            SplitNotes(
                k1 = fresh,
                change = change,
                signature = response.signature,
                changeSignature = response.changeSignature,
            )
        }
    }

    /** Burn all the given notes; mint one worth their sum. */
    public suspend fun merge(callback: String, k1s: List<String>): MutationOutcome<RotatedNote> {
        val fresh = secretSource()
        return mutate({ mergeRequest(callback, k1s, fresh) }) { response ->
            RotatedNote(k1 = fresh, signature = response.signature)
        }
    }

    /**
     * Burn a note; the service pays [invoice] of exactly its value. Merge
     * several notes first to melt them together.
     *
     * A [MutationOutcome.Confirmed] here means the payment is IN FLIGHT, not
     * that the note is spent - see [MeltReceipt].
     */
    public suspend fun melt(
        callback: String,
        k1: String,
        invoice: String,
    ): MutationOutcome<MeltReceipt> =
        mutate({ meltRequest(callback, k1, invoice) }) { response ->
            MeltReceipt(invoice = response.pr, verifyUrl = response.verify)
        }

    /**
     * After a [MutationOutcome.Unknown]: did the burn actually happen?
     *
     * Probe one of the inputs. [NoteFate.LIVE] means the request never landed
     * and the preserved secrets minted nothing; [NoteFate.GONE] means the burn
     * landed and those secrets are the only money left; [NoteFate.UNKNOWN]
     * means keep everything and try again later.
     */
    public suspend fun probeBurnedNote(url: String): NoteFate = try {
        fetchNoteInfo(url)
        NoteFate.LIVE
    } catch (err: LnurlcashException.NoteSpent) {
        NoteFate.GONE
    } catch (err: LnurlcashException.NoteUnknown) {
        NoteFate.GONE
    } catch (err: Throwable) {
        NoteFate.UNKNOWN
    }

    /**
     * Resolve what a split's change or a merge's output is actually worth, then
     * rotate it.
     *
     * Neither response carries an amount, and a fee-charging service may have
     * deducted from a split's change or refunded into a merge's result. The
     * authoritative value comes from the informational GET - which puts the
     * secret on the wire, so a rotate follows, best-effort.
     */
    public suspend fun settleNote(
        baseUrl: String,
        k1: String,
        expectedAmountMsat: Long,
        signature: String? = null,
    ): SettledNote {
        val url = withNewK1(baseUrl, k1, expectedAmountMsat, signature)
            ?: throw LnurlcashException.RequestRefused("that note URL does not parse")
        val info = fetchNoteInfo(url)
        return when (val rotated = rotate(info.callback, k1)) {
            is MutationOutcome.Confirmed -> SettledNote(
                k1 = rotated.value.k1,
                amountMsat = info.maxWithdrawableMsat,
                signature = rotated.value.signature,
                callback = info.callback,
            )
            else -> SettledNote(
                k1 = k1,
                amountMsat = info.maxWithdrawableMsat,
                signature = signature,
                callback = info.callback,
            )
        }
    }

    public suspend fun fetchPayRequest(url: String): PayRequest {
        val info = parsePayRequest(get(payRequestRequest(url)))
        return PayRequest(
            callback = info.callback,
            minSendableMsat = info.minSendable.toLong(),
            maxSendableMsat = info.maxSendable.toLong(),
            metadata = info.metadata,
            withdrawLink = info.withdrawLink,
            mintPubkey = info.mintPubkey,
            mintFee = info.mintFee?.toKotlin(),
        )
    }

    public suspend fun requestInvoice(payCallback: String, amountMsat: Long): Invoice {
        val body = get(invoiceRequest(payCallback, amountMsat.toULong()))
        val invoice = parseInvoice(body, amountMsat.toULong())
        return Invoice(
            invoice = invoice.pr,
            verifyUrl = invoice.verify,
            disposable = invoice.disposable,
        )
    }

    /**
     * LUD-21: has this invoice settled?
     *
     * If it discloses a preimage, that preimage IS the note's spend secret and
     * the service saw it. Anyone who saw the unpaid invoice can poll this too -
     * the payment hash travels inside the invoice. Rotate immediately.
     */
    public suspend fun fetchInvoiceStatus(verifyUrl: String): InvoiceStatus {
        val result = parseVerify(get(verifyRequest(verifyUrl)))
        return InvoiceStatus(
            settled = result.settled,
            preimage = result.preimage,
            invoice = result.pr,
        )
    }

    /** Best-effort discovery. Experimental and optional; most services lack it. */
    public suspend fun fetchMintAddress(url: String): MintAddress {
        val info = parseMintAddress(get(mintAddressRequest(url)))
        return MintAddress(
            callback = info.callback,
            payLink = info.payLink,
            maxWithdrawableMsat = info.maxWithdrawable.toLong(),
            minWithdrawableMsat = info.minWithdrawable.toLong(),
            nodePubkey = info.nodePubkey,
            nodeAlias = info.nodeAlias,
            nodeUri = info.nodeUri,
            nodeColor = info.nodeColor,
            nodeCapacityMsat = info.nodeCapacityMsat?.toLong(),
            nodeNumChannels = info.nodeNumChannels?.toLong(),
            nodeNumPeers = info.nodeNumPeers?.toLong(),
        )
    }

    // ---- the plumbing ----

    private suspend fun get(request: FfiRequest): String {
        if (offline) {
            throw LnurlcashException.RequestRefused("offline mode is on - no request was made")
        }
        if (!isAllowedServiceUrl(request.url)) {
            throw LnurlcashException.RequestRefused(
                "refusing to fetch that URL - only https, or http to a loopback or .onion host, is allowed",
            )
        }
        val call = http.newCall(Request.Builder().url(request.url).get().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { continuation.resume(it.body?.string() ?: "") }
                }
            })
        }
    }

    /**
     * Perform a mutation and classify what happened.
     *
     * Every failure between here and a parsed answer is an unknown outcome
     * carrying the fresh secrets - including a refused connection, which may
     * still have been refused *after* the service processed the request.
     */
    private suspend fun <T> mutate(
        buildRequest: () -> FfiRequest,
        onConfirmed: (uniffi.lnurlcash_core.FfiMutation) -> T,
    ): MutationOutcome<T> {
        // Building can refuse - a callback URL this library will not fetch, a
        // mutation naming no note. That is a definitive "nothing was sent", and
        // it belongs in the outcome rather than thrown past it: a caller
        // matching on MutationOutcome should not also need a try/catch.
        val request = try {
            buildRequest()
        } catch (err: LnurlcashException) {
            return MutationOutcome.Rejected(err)
        }
        val body = try {
            get(request)
        } catch (err: LnurlcashException.RequestRefused) {
            // nothing left the process - the note is untouched
            return MutationOutcome.Rejected(err)
        } catch (err: Throwable) {
            return MutationOutcome.Unknown(
                newSecrets = request.newSecrets,
                message = "the service could not be reached, or its answer was lost - " +
                    "the mutation may still have been applied",
                cause = err,
            )
        }
        return try {
            MutationOutcome.Confirmed(onConfirmed(parseMutation(body, request.newSecrets)))
        } catch (err: LnurlcashException.Ambiguous) {
            MutationOutcome.Unknown(
                newSecrets = err.newSecrets,
                message = err.detail,
                cause = err,
            )
        } catch (err: LnurlcashException) {
            MutationOutcome.Rejected(err)
        }
    }
}
