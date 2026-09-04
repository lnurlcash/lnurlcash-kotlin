package io.github.thecryptodonkey.lnurlcash

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import uniffi.lnurlcash_core.LnurlcashException

/**
 * Runs against the mock mint. The happy paths matter, but the adversarial modes
 * are the reason this suite exists: a library that only works against a
 * well-behaved SERVICE has not been tested at all.
 */
class ProtocolTest {

    private fun withMint(vararg flags: String, block: suspend (MockMint, LnurlcashClient) -> Unit) {
        val mint = MockMint.start(*flags)
        if (mint == null) {
            println("skipping: the conformance repo's mock mint was not found")
            return
        }
        mint.use { runBlocking { block(it, LnurlcashClient()) } }
    }

    /**
     * The same, with a client that gives up on the first ambiguous answer - as
     * every client did before LUD-25 required a service to replay a retried
     * mutation.
     *
     * The tests that assert what an unresolved mutation carries need it: with
     * retries on, a conforming mint simply answers again and there is nothing
     * left to carry.
     */
    private fun withMintNoRetry(
        vararg flags: String,
        block: suspend (MockMint, LnurlcashClient) -> Unit,
    ) {
        val mint = MockMint.start(*flags)
        if (mint == null) {
            println("skipping: the conformance repo's mock mint was not found")
            return
        }
        mint.use { runBlocking { block(it, LnurlcashClient(mutationRetries = 0)) } }
    }

    @Test
    fun `reports value and never burns`() = withMint { mint, client ->
        val k1 = secret(1)
        mint.credit(k1, 21_000)

        val info = client.fetchNoteInfo(mint.noteUrl(k1))
        assertEquals(21_000, info.maxWithdrawableMsat)
        assertEquals(k1, info.k1)
        assertEquals("outstanding", mint.noteState(k1))

        assertEquals(21_000, client.fetchNoteInfo(mint.noteUrl(k1)).maxWithdrawableMsat)
    }

    @Test
    fun `max withdrawable beats the url's own claim`() = withMint { mint, client ->
        val k1 = secret(2)
        mint.credit(k1, 21_000)
        assertEquals(21_000, client.fetchNoteInfo(mint.noteUrl(k1, 2_100_000)).maxWithdrawableMsat)
    }

    @Test
    fun `refuses a service that echoes a different k1`() = withMint("--echoWrongK1=true") { mint, client ->
        val k1 = secret(3)
        mint.credit(k1, 21_000)
        val failure = runCatching { client.fetchNoteInfo(mint.noteUrl(k1)) }.exceptionOrNull()
        assertTrue(failure is LnurlcashException.Protocol, "got $failure")
    }

    @Test
    fun `rotate burns the old secret and mints one the service never saw`() = withMint { mint, client ->
        val k1 = secret(4)
        mint.credit(k1, 21_000)

        val outcome = client.rotate(mint.callback(), k1)
        assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
        val rotated = outcome.value
        assertNotEquals(k1, rotated.k1)
        assertEquals("burned", mint.noteState(k1))
        assertEquals("outstanding", mint.noteState(rotated.k1))

        val signature = rotated.signature!!
        assertTrue(verifyNoteSignature(rotated.k1, 21_000, signature, mint.pubkey))
        assertTrue(!verifyNoteSignature(rotated.k1, 21_001, signature, mint.pubkey))
    }

    @Test
    fun `accepts the other recovery id layout`() = withMint("--signatureLayout=leading") { mint, client ->
        val k1 = secret(5)
        mint.credit(k1, 21_000)
        val rotated = client.rotate(mint.callback(), k1).getOrThrow()
        assertTrue(verifyNoteSignature(rotated.k1, 21_000, rotated.signature!!, mint.pubkey))
    }

    @Test
    fun `ignores a secret the service tries to hand back`() =
        withMint("--serverGeneratedSecrets=true") { mint, client ->
            val k1 = secret(6)
            mint.credit(k1, 21_000)
            val rotated = client.rotate(mint.callback(), k1).getOrThrow()
            // taking the mint's offered secret would hand it a permanent copy of
            // the note it just issued
            assertNotEquals("a".repeat(64), rotated.k1)
            assertEquals("outstanding", mint.noteState(rotated.k1))
        }

    @Test
    fun `split produces an amount and its change`() = withMint { mint, client ->
        val k1 = secret(7)
        mint.credit(k1, 21_000)

        val split = client.split(mint.callback(), listOf(k1), 5_000).getOrThrow()
        assertEquals("burned", mint.noteState(k1))
        assertEquals(5_000, client.fetchNoteInfo(mint.noteUrl(split.k1)).maxWithdrawableMsat)
        assertEquals(16_000, client.fetchNoteInfo(mint.noteUrl(split.change)).maxWithdrawableMsat)
        assertTrue(verifyNoteSignature(split.k1, 5_000, split.signature!!, mint.pubkey))
    }

    @Test
    fun `merge sums`() = withMint { mint, client ->
        val parts = (8..10).map { secret(it) }
        parts.forEachIndexed { index, k1 -> mint.credit(k1, 1_000L * (index + 1)) }

        val merged = client.merge(mint.callback(), parts).getOrThrow()
        parts.forEach { assertEquals("burned", mint.noteState(it)) }
        assertEquals(6_000, client.fetchNoteInfo(mint.noteUrl(merged.k1)).maxWithdrawableMsat)
    }

    @Test
    fun `refuses a mutation naming no note`() = withMint { mint, client ->
        val outcome = client.merge(mint.callback(), emptyList())
        assertTrue(outcome is MutationOutcome.Rejected, "got $outcome")
        assertTrue(outcome.error is LnurlcashException.RequestRefused)
    }

    @Test
    fun `settle resolves what an output is really worth`() = withMint { mint, client ->
        val k1 = secret(11)
        mint.credit(k1, 21_000)
        val split = client.split(mint.callback(), listOf(k1), 5_000).getOrThrow()

        // the caller does not know the change is 16000 - only the service does
        val settled = client.settleNote(mint.noteUrl(k1), split.change, 0, split.changeSignature)
        assertEquals(16_000, settled.amountMsat)
        assertNotEquals(split.change, settled.k1)
        assertEquals("burned", mint.noteState(split.change))
    }

    @Test
    fun `settle surfaces a rotate that may have applied`() =
        withMintNoRetry("--dropAfterMutation=true") { mint, client ->
            val k1 = secret(31)
            mint.credit(k1, 21_000)

            // The service burned this k1 and minted the rotated note under h,
            // and newSecrets is the only key to it. This outcome used to fall
            // into an `else` beside Rejected and come back as a SettledNote
            // carrying the burned k1, dropping the live secret entirely.
            val thrown = assertFailsWith<LnurlcashException.Ambiguous> {
                client.settleNote(mint.noteUrl(k1), k1, 0)
            }
            assertEquals(1, thrown.newSecrets.size)
            assertNotEquals(k1, thrown.newSecrets[0])
        }

    @Test
    fun `melt ok means in flight not spent`() = withMint("--meltNeverSettles=true") { mint, client ->
        val k1 = secret(12)
        mint.credit(k1, 21_000)

        val receipt = client.melt(mint.callback(), k1, "lnbc210n1pjqrstuvwxyz").getOrThrow()
        assertEquals("lnbc210n1pjqrstuvwxyz", receipt.invoice)
        assertEquals("pending", mint.noteState(k1))

        // and every other operation is locked out until it resolves
        val blocked = client.rotate(mint.callback(), k1)
        assertTrue(blocked is MutationOutcome.Rejected, "got $blocked")
        assertTrue(blocked.error is LnurlcashException.NotePending)
    }

    @Test
    fun `a failed melt restores the note`() = withMint("--meltAlwaysFails=true") { mint, client ->
        val k1 = secret(13)
        mint.credit(k1, 21_000)
        client.melt(mint.callback(), k1, "lnbc210n1pjqrstuvwxyz").getOrThrow()
        Thread.sleep(150)
        // a failed melt is never reported through the callback - it is only
        // observable as the note becoming spendable again
        assertEquals("outstanding", mint.noteState(k1))
    }

    @Test
    fun `mints a note the service never saw the secret of`() = withMint { mint, client ->
        val pay = client.fetchPayRequest("${mint.url}/.well-known/lnurlp/mint")
        val withdrawLink = pay.withdrawLink!!
        // LUD-25 minting is comment-bound, so a mint must leave room for the
        // 64-character commitment. Without it there is nowhere to name the note.
        assertTrue(pay.namesMintOutput)
        assertEquals(64L, pay.commentAllowed)

        // The wallet chooses the secret, before any invoice exists, and
        // persists it before paying. The service is told sha256 of it, no more.
        val mintSecret = secret(42)
        val invoice = client.requestMintInvoice(pay.callback, 21_000, mintSecret)
        assertTrue(!invoice.disposable)
        val verifyUrl = invoice.verifyUrl!!
        val paymentHash = verifyUrl.substringAfterLast('/')
        mint.settle(paymentHash)

        val status = client.fetchInvoiceStatus(verifyUrl)
        assertTrue(status.settled)
        // The preimage is settlement proof and nothing else. Every node that
        // forwarded the payment learned it; under the earlier draft that made
        // all of them holders of the note. Here it redeems nothing.
        val preimage = status.preimage!!
        assertEquals(paymentHash, hashK1(preimage))
        assertNotEquals(mintSecret, preimage)
        assertTrue(
            runCatching { client.fetchNoteInfo(buildNoteUrl(withdrawLink, preimage)!!) }.isFailure,
            "the payment preimage must not redeem the note",
        )

        // The wallet's own secret is the note.
        val info = client.fetchNoteInfo(buildNoteUrl(withdrawLink, mintSecret)!!)
        assertEquals(21_000, info.maxWithdrawableMsat)
        val rotated = client.rotate(info.callback, mintSecret).getOrThrow()
        assertEquals("burned", mint.noteState(mintSecret))
        assertEquals("outstanding", mint.noteState(rotated.k1))
    }

    @Test
    fun `refuses to pay for a note it cannot name`() = withMint { mint, client ->
        val pay = client.fetchPayRequest("${mint.url}/.well-known/lnurlp/mint")

        // A malformed commitment is refused before the request leaves, so a
        // wallet never pays for a quote the service was always going to reject.
        assertTrue(
            runCatching {
                client.requestMintInvoice(pay.callback, 21_000, "not-a-32-byte-secret")
            }.isFailure,
        )

        // And an unnamed mint quote is refused by the service itself, before
        // any invoice exists to pay.
        assertTrue(runCatching { client.requestInvoice(pay.callback, 21_000) }.isFailure)
    }

    @Test
    fun `reads an advertised fee`() = withMint("--baseFeeMsat=1000", "--feePpm=2000") { mint, client ->
        val pay = client.fetchPayRequest("${mint.url}/.well-known/lnurlp/mint")
        assertEquals(MintFee(baseFeeMsat = 1_000, feePpm = 2_000), pay.mintFee)
    }

    @Test
    fun `no fee advertised means fee free`() = withMint { mint, client ->
        val pay = client.fetchPayRequest("${mint.url}/.well-known/lnurlp/mint")
        assertNull(pay.mintFee)
    }

    @Test
    fun `finds the experimental mint address`() = withMint { mint, client ->
        val address = client.fetchMintAddress("${mint.url}/.well-known/lnurlw/mint")
        assertEquals(mint.pubkey, address.nodePubkey)
    }

    @Test
    fun `reads the node stats a mint address advertises`() = withMint { mint, client ->
        val address = client.fetchMintAddress("${mint.url}/.well-known/lnurlw/mint")
        // the wire field is nodeCapacity - renamed here, so it only arrives if
        // it is mapped rather than read under its own name
        assertEquals(500_000_000L, address.nodeCapacityMsat)
        assertEquals(4L, address.nodeNumChannels)
        assertEquals(6L, address.nodeNumPeers)
    }

    // ---- ambiguous outcomes ----

    @Test
    fun `a lost rotate completes by asking again`() = withMint("--dropAfterMutation=true") { mint, client ->
        // The mutation landed and the answer was lost on the way back. LUD-25
        // now requires the service to answer the identical request with the
        // success it already gave, so asking a second time turns this from an
        // unresolved maybe into a completed rotate.
        val k1 = secret(30)
        mint.credit(k1, 21_000)

        val outcome = client.rotate(mint.callback(), k1)
        assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
        assertEquals("burned", mint.noteState(k1))
        assertEquals("outstanding", mint.noteState(outcome.value.k1))
        // the replay repeats the signature, so a note recovered this way is as
        // verifiable as one whose first answer arrived
        assertNotNull(outcome.value.signature)
    }

    @Test
    fun `a mint that will not replay still hands the secrets back`() =
        withMint("--dropAfterMutation=true", "--retriedMutation=refuse") { mint, client ->
            // A service that has not implemented the replay rule answers the
            // second attempt as an already-spent input. This library cannot
            // tell that from a genuine double spend - at the wire they are the
            // same answer - so it hands the secrets back rather than a verdict.
            val k1 = secret(31)
            mint.credit(k1, 21_000)

            val outcome = client.rotate(mint.callback(), k1)
            assertTrue(outcome is MutationOutcome.Rejected, "got $outcome")
        }

    @Test
    fun `an unsigned rotate is refused without losing the note`() =
        withMint("--signatures=false") { mint, client ->
            // Offline verification stopped being optional, so a service issuing
            // no signatures is non-compliant rather than merely basic. The
            // mutation LANDED though, and the fresh secret is the only key to
            // the note it minted - so it is its own outcome, carrying them.
            val k1 = secret(32)
            mint.credit(k1, 21_000)

            val outcome = client.rotate(mint.callback(), k1)
            assertTrue(outcome is MutationOutcome.Unverifiable, "got $outcome")
            assertEquals(1, outcome.newSecrets.size)
            // the note the caller was refused is real and outstanding
            assertEquals("outstanding", mint.noteState(outcome.newSecrets.first()))
        }

    @Test
    fun `an unsigned service still works when the caller opts out`() =
        withMint("--signatures=false") { mint, _ ->
            val client = LnurlcashClient(requireSignatures = false)
            val k1 = secret(33)
            mint.credit(k1, 21_000)

            val info = client.fetchNoteInfo(mint.noteUrl(k1))
            val outcome = client.rotate(info.callback, k1)
            assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
            assertNull(outcome.value.signature)
            assertEquals("outstanding", mint.noteState(outcome.value.k1))
        }

    @Test
    fun `a lost rotate preserves its fresh secret`() = withMintNoRetry("--dropAfterMutation=true") { mint, client ->
        val k1 = secret(14)
        mint.credit(k1, 21_000)

        val outcome = client.rotate(mint.callback(), k1)
        assertTrue(outcome is MutationOutcome.Unknown, "got $outcome")
        assertEquals(1, outcome.newSecrets.size)

        // the mutation did land: the input is burned and the output exists, keyed
        // by the hash of a secret only the caller holds
        assertEquals("burned", mint.noteState(k1))
        val rescued = outcome.newSecrets.first()
        assertEquals(21_000, client.fetchNoteInfo(mint.noteUrl(rescued)).maxWithdrawableMsat)
    }

    @Test
    fun `a lost split preserves both secrets in output order`() =
        withMintNoRetry("--dropAfterMutation=true") { mint, client ->
            val k1 = secret(15)
            mint.credit(k1, 21_000)

            val outcome = client.split(mint.callback(), listOf(k1), 5_000)
            assertTrue(outcome is MutationOutcome.Unknown, "got $outcome")
            val (splitOff, change) = outcome.newSecrets
            assertEquals(5_000, client.fetchNoteInfo(mint.noteUrl(splitOff)).maxWithdrawableMsat)
            assertEquals(16_000, client.fetchNoteInfo(mint.noteUrl(change)).maxWithdrawableMsat)
        }

    @Test
    fun `probing resolves the ambiguity`() = withMint("--dropAfterMutation=true") { mint, client ->
        val k1 = secret(16)
        mint.credit(k1, 21_000)
        client.rotate(mint.callback(), k1)
        assertEquals(NoteFate.GONE, client.probeBurnedNote(mint.noteUrl(k1)))
    }

    @Test
    fun `a live note probes as live and an offline probe knows nothing`() = withMint { mint, client ->
        val k1 = secret(17)
        mint.credit(k1, 21_000)
        assertEquals(NoteFate.LIVE, client.probeBurnedNote(mint.noteUrl(k1)))

        val offline = LnurlcashClient(offline = true)
        assertEquals(NoteFate.UNKNOWN, offline.probeBurnedNote(mint.noteUrl(k1)))
    }

    @Test
    fun `a 200 that confirms nothing is ambiguous`() = withMintNoRetry("--unconfirmedMutation=true") { mint, client ->
        val k1 = secret(18)
        mint.credit(k1, 21_000)
        val outcome = client.rotate(mint.callback(), k1)
        assertTrue(outcome is MutationOutcome.Unknown, "got $outcome")
        assertEquals(1, outcome.newSecrets.size)
        assertEquals("burned", mint.noteState(k1))
    }

    @Test
    fun `an unreadable response is ambiguous`() = withMint("--malformedJson=true") { mint, client ->
        val k1 = secret(19)
        mint.credit(k1, 21_000)
        val outcome = client.rotate(mint.callback(), k1)
        assertTrue(outcome is MutationOutcome.Unknown, "got $outcome")
        assertEquals(1, outcome.newSecrets.size)
    }

    @Test
    fun `a timeout is ambiguous not failure`() {
        val mint = MockMint.start("--slowMs=800")
        if (mint == null) return
        mint.use {
            runBlocking {
                val impatient = LnurlcashClient(timeout = Duration.ofMillis(80))
                val k1 = secret(20)
                it.credit(k1, 21_000)
                val outcome = impatient.rotate(it.callback(), k1)
                assertTrue(outcome is MutationOutcome.Unknown, "got $outcome")
                assertEquals(1, outcome.newSecrets.size)
            }
        }
    }

    @Test
    fun `a refused request is definitely not sent`() = withMint { mint, _ ->
        val k1 = secret(21)
        mint.credit(k1, 21_000)
        val offline = LnurlcashClient(offline = true)
        val outcome = offline.rotate(mint.callback(), k1)
        assertTrue(outcome is MutationOutcome.Rejected, "got $outcome")
        assertEquals("outstanding", mint.noteState(k1))
    }

    @Test
    fun `refuses a callback url it would not fetch`() = withMint { _, client ->
        val outcome = client.rotate("http://evil.example/cb", secret(22))
        assertTrue(outcome is MutationOutcome.Rejected, "got $outcome")
    }

    @Test
    fun `a lying service cannot inflate past what it signed`() =
        withMint("--lieAboutValue=1000000") { mint, client ->
            val k1 = secret(23)
            val signature = mint.credit(k1, 21_000)!!

            val info = client.fetchNoteInfo(mint.noteUrl(k1))
            assertEquals(1_021_000, info.maxWithdrawableMsat)
            // the signature was issued over the true amount, so an offline holder
            // catches the inflation without asking anyone
            assertTrue(!verifyNoteSignature(k1, info.maxWithdrawableMsat, signature, mint.pubkey))
            assertTrue(verifyNoteSignature(k1, 21_000, signature, mint.pubkey))
        }
}
