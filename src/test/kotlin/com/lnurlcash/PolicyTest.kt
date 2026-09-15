package com.lnurlcash

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import uniffi.lnurlcash_core.LnurlcashException

/**
 * What a mutation owes, and what a withdrawRequest must carry across the
 * legacy hash and Part 2 key forms.
 *
 * All of it through [LnurlcashClient] over a loopback service. The rules
 * themselves live in the core and are graded there; what is unique to this
 * repo is that the client builds its policy from its own options and hands the
 * core the outputs each request named. Get either wrong and the core's own
 * tests still pass.
 */
class PolicyTest {

    @BeforeTest
    fun requireVectors() {
        assertTrue(Vectors.available, "conformance vectors not found at ${Vectors.directory}")
    }

    private val part2: JsonObject by lazy { Vectors.load("part2.json") }
    private val cp1s: List<String> by lazy {
        part2.array("branches")[0].jsonObject.array("notes").take(2).map { it.jsonObject.str("cp1") }
    }
    private val mintPubkey: String by lazy { part2.obj("mint").str("mintPubkey") }

    private val k1 = "11".repeat(32)
    private val hash = hashK1("22".repeat(32))
    private val change = hashK1("33".repeat(32))
    private val signature = "ab".repeat(65)
    private val changeSignature = "cd".repeat(65)

    private val bareOk = """{"status":"OK"}"""
    private val firstSigned = """{"status":"OK","sig":"$signature"}"""
    private val bothSigned = """{"status":"OK","sig":"$signature","sig2":"$changeSignature"}"""

    /** One call against a service answering [answers], and what it came back as. */
    private fun <T> against(
        vararg answers: CannedService.Answer,
        call: suspend (callback: String) -> MutationOutcome<T>,
    ): Pair<MutationOutcome<T>, CannedService> {
        val service = CannedService(*answers)
        return service.use { runBlocking { call("${it.url}/w/cb") } to it }
    }

    private fun json(body: String) = CannedService.Answer.Json(body)

    /** Secrets drawn in order, so an outcome can be checked for carrying exactly them. */
    private class Drawn {
        val secrets = mutableListOf<String>()
        fun next(): String = secret(0x40 + secrets.size).also { secrets.add(it) }
    }

    @Test
    fun `the default tolerates a no-signer legacy hash output`() {
        val client = LnurlcashClient()
        val outcomes = listOf(
            against(json(bareOk)) { client.rotate(it, k1) },
            against(json(bareOk)) { client.merge(it, listOf(k1, secret(9))) },
            against(json(bareOk)) { client.split(it, listOf(k1), 5_000) },
            against(json(bareOk)) { client.rotateWithHash(it, k1, hash) },
            against(json(bareOk)) { client.mergeWithHash(it, listOf(k1), hash) },
            against(json(bareOk)) { client.splitWithHash(it, listOf(k1), 5_000, hash, change) },
        ).map { it.first }
        for (outcome in outcomes) {
            assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
            val signatures = when (val value = outcome.value) {
                is RotatedNote -> listOf(value.signature)
                is SplitNotes -> listOf(value.signature, value.changeSignature)
                is HashedNote -> listOf(value.signature)
                is HashedSplitNotes -> listOf(value.signature, value.changeSignature)
                else -> error("a result this test does not know: $value")
            }
            assertTrue(signatures.all { it == null }, "no-signer mode returned proof: $signatures")
        }
        // and the reference mint's raw Part 1 signature passes through as sent
        val (signed, _) = against(json(firstSigned)) { client.rotateWithHash(it, k1, hash) }
        assertEquals(MutationOutcome.Confirmed(HashedNote(signature)), signed)
    }

    @Test
    fun `an uncertified cp1 output is unverifiable even with the option off`() {
        for (client in listOf(LnurlcashClient(), LnurlcashClient(requireSignatures = false))) {
            for ((outcome, _) in listOf(
                against(json(bareOk)) { client.rotateWithHash(it, k1, cp1s[0]) },
                against(json(bareOk)) { client.mergeWithHash(it, listOf(k1, secret(9)), cp1s[0]) },
                against(json(bareOk)) { client.splitWithHash(it, listOf(k1), 5_000, cp1s[0], change) },
            )) {
                assertTrue(outcome is MutationOutcome.Unverifiable, "got $outcome")
                // the caller named the output, so there is nothing of ours to carry
                assertTrue(outcome.newSecrets.isEmpty(), "${outcome.newSecrets}")
                assertTrue("cp1" in outcome.message, outcome.message)
            }
        }
        // certified, it is simply a success, the certificate handed back
        val (certified, _) = against(json(firstSigned)) { LnurlcashClient().rotateWithHash(it, k1, cp1s[0]) }
        assertEquals(MutationOutcome.Confirmed(HashedNote(signature)), certified)
    }

    @Test
    fun `a cp1 change without sig2 is unverifiable`() {
        val client = LnurlcashClient()
        // the change is a cp1 note, owed its certificate in sig2 exactly as a
        // first output is owed one in sig: the change is not a lesser note
        val (uncertified, _) = against(json(firstSigned)) {
            client.splitWithHash(it, listOf(k1), 5_000, hash, cp1s[1])
        }
        assertTrue(uncertified is MutationOutcome.Unverifiable, "got $uncertified")
        assertTrue("change" in uncertified.message, uncertified.message)

        // the other way round the change is a legacy hash accepted in no-signer mode
        val (plainChange, _) = against(json(firstSigned)) {
            client.splitWithHash(it, listOf(k1), 5_000, cp1s[0], change)
        }
        assertEquals(MutationOutcome.Confirmed(HashedSplitNotes(signature, null)), plainChange)

        // and two cp1 outputs, both certified, are a success
        val (both, _) = against(json(bothSigned)) {
            client.splitWithHash(it, listOf(k1), 5_000, cp1s[0], cp1s[1])
        }
        assertEquals(MutationOutcome.Confirmed(HashedSplitNotes(signature, changeSignature)), both)
    }

    @Test
    fun `requireSignatures still refuses an unsigned hash output`() {
        // The mutation LANDED, so the refusal carries every drawn secret, in
        // output order: refusing without them would strand real money to make
        // a point about a signature.
        val drawn = Drawn()
        val strict = LnurlcashClient(requireSignatures = true, secretSource = drawn::next)

        val (rotated, _) = against(json(bareOk)) { strict.rotate(it, k1) }
        assertTrue(rotated is MutationOutcome.Unverifiable, "got $rotated")
        assertEquals(drawn.secrets, rotated.newSecrets)

        drawn.secrets.clear()
        val (split, _) = against(json(firstSigned)) { strict.split(it, listOf(k1), 5_000) }
        assertTrue(split is MutationOutcome.Unverifiable, "got $split")
        assertEquals(drawn.secrets, split.newSecrets)
        assertEquals(2, split.newSecrets.size)
        assertTrue("change" in split.message, split.message)

        // a caller-named hash output is refused too, with nothing of ours to carry
        val (named, _) = against(json(bareOk)) { strict.rotateWithHash(it, k1, hash) }
        assertTrue(named is MutationOutcome.Unverifiable, "got $named")
        assertTrue(named.newSecrets.isEmpty(), "${named.newSecrets}")

        // signed throughout it is fine, and a melt mints nothing, so owes nothing
        val (signed, _) = against(json(bothSigned)) { strict.split(it, listOf(k1), 5_000) }
        assertTrue(signed is MutationOutcome.Confirmed, "got $signed")
        val (melt, _) = against(json(bareOk)) { strict.melt(it, k1, "lnbc210n1pjq") }
        assertTrue(melt is MutationOutcome.Confirmed, "got $melt")
    }

    @Test
    fun `requireMintPubkey refuses a note with no key, by default and only by its own option`() {
        fun withdrawRequest(key: String?): String =
            """{"tag":"withdrawRequest","callback":"https://mint.example/w/cb","k1":"$k1",""" +
                """"minWithdrawable":1000,"maxWithdrawable":21000""" +
                (if (key == null) "" else ""","mintPubkey":"$key"""") + "}"

        fun fetch(client: LnurlcashClient, body: String): NoteInfo =
            CannedService(body).use { service ->
                runBlocking { client.fetchNoteInfo("${service.url}/w?k1=$k1") }
            }

        for (missing in listOf(null, "not a key")) {
            // refused by default, and requiring signatures does not change
            // that: the two options no longer ride together
            for (client in listOf(LnurlcashClient(), LnurlcashClient(requireSignatures = true))) {
                assertFailsWith<LnurlcashException.Protocol>("$missing") {
                    fetch(client, withdrawRequest(missing))
                }
            }
            // and admitted, as it is, once the caller opts out
            for (client in listOf(
                LnurlcashClient(requireMintPubkey = false),
                LnurlcashClient(requireSignatures = true, requireMintPubkey = false),
            )) {
                val info = fetch(client, withdrawRequest(missing))
                assertEquals(missing, info.mintPubkey)
                assertEquals(21_000, info.maxWithdrawableMsat)
            }
        }
        // a published key comes back in one spelling
        assertEquals(mintPubkey, fetch(LnurlcashClient(), withdrawRequest(mintPubkey.uppercase())).mintPubkey)
    }

    @Test
    fun `a re-sent mutation still knows its outputs`() {
        // The first answer is lost; the client re-sends the SAME request, and
        // the second answer is a bare OK. The output is a cp1, so that is
        // still an uncertified note - which the client can only know if the
        // re-sent request carried its outputs through to the parser.
        val (keyed, keyedService) = against(CannedService.Answer.Drop, json(bareOk)) {
            LnurlcashClient().rotateWithHash(it, k1, cp1s[0])
        }
        assertTrue(keyed is MutationOutcome.Unverifiable, "got $keyed")
        assertEquals(2, keyedService.requests.size)
        assertEquals(keyedService.requests[0], keyedService.requests[1], "byte-identical, or it is a second burn")

        // the same lost answer to a hash output completes as a success
        val (plain, plainService) = against(CannedService.Answer.Drop, json(bareOk)) {
            LnurlcashClient().rotateWithHash(it, k1, hash)
        }
        assertEquals(MutationOutcome.Confirmed(HashedNote(null)), plain)
        assertEquals(2, plainService.requests.size)

        // and a generated output keeps its secret across the re-send
        val drawn = Drawn()
        val (generated, _) = against(CannedService.Answer.Drop, json(bareOk)) {
            LnurlcashClient(secretSource = drawn::next).rotate(it, k1)
        }
        assertTrue(generated is MutationOutcome.Confirmed, "got $generated")
        assertEquals(drawn.secrets.single(), generated.value.k1)
        assertNull(generated.value.signature)
    }
}
