package com.lnurlcash

import java.math.BigInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import uniffi.lnurlcash_core.LnurlcashException

/**
 * The LUD-25 Part 2 wire, through [LnurlcashClient]: a `ck1` wherever a k1
 * goes, a `cp1` wherever an output goes.
 *
 * Driven over real HTTP against a loopback server that records what it was
 * asked and answers with a canned body. The mock mint does not speak Part 2,
 * and what matters here is what leaves this library and what it accepts
 * back, which a stub of the HTTP client would not show: the requests below
 * are built by the core, cross the FFI, and go out through OkHttp exactly as
 * they would to a real mint.
 */
class Part2WireTest {

    @BeforeTest
    fun requireVectors() {
        assertTrue(Vectors.available, "conformance vectors not found at ${Vectors.directory}")
    }

    private val part2: JsonObject by lazy { Vectors.load("part2.json") }
    private val mintPubkey: String by lazy { part2.obj("mint").str("mintPubkey") }
    private val notes: List<JsonObject> by lazy {
        part2.array("branches")[0].jsonObject.array("notes").map { it.jsonObject }
    }

    private val k1 = "11".repeat(32)

    // ---- the echo on an informational GET ----

    private fun withdrawInfo(echoed: String): String =
        """{"tag":"withdrawRequest","callback":"https://mint.example/w/cb","k1":"$echoed",""" +
            """"minWithdrawable":21000,"maxWithdrawable":21000,"mintPubkey":"$mintPubkey"}"""

    @Test
    fun `a service echoing another valid ck1 for the same note has named the same note`() {
        val ours = notes[0].str("ck1")
        val twin = highSTwin(ours)
        assertNotEquals(ours, twin, "the twin is a different string")
        assertEquals(noteIdOf(ours), noteIdOf(twin), "and the same note")

        for (echoed in listOf(ours, ours.uppercase(), twin)) {
            CannedService(withdrawInfo(echoed)).use { service ->
                val info = runBlocking {
                    LnurlcashClient().fetchNoteInfo("${service.url}/w?k1=$ours&amount=21000")
                }
                assertEquals(21_000, info.maxWithdrawableMsat, echoed)
                assertEquals(noteIdOf(ours), noteIdOf(info.k1), echoed)
                // the ck1 went out as itself, never as its id
                assertEquals(ours, service.param("k1"))
            }
        }
    }

    @Test
    fun `a service echoing a different note, or no note, is refused`() {
        val ours = notes[0].str("ck1")
        val refused = listOf(
            notes[1].str("ck1"), // another note on the same branch
            k1, // a Part 1 secret
            noteIdOf(ours)!!, // the note's id is not its k1
            "zz", // nothing at all
        )
        for (echoed in refused) {
            CannedService(withdrawInfo(echoed)).use { service ->
                val failure = runCatching {
                    runBlocking { LnurlcashClient().fetchNoteInfo("${service.url}/w?k1=$ours&amount=21000") }
                }.exceptionOrNull()
                assertTrue(failure is LnurlcashException.Protocol, "$echoed must be refused, got $failure")
            }
        }
    }

    // ---- mutations into outputs the caller holds ----

    @Test
    fun `a ck1 rotates into a cp1, which goes as p1`() {
        val ours = notes[4].str("ck1")
        val output = notes[1] // the note certificates[1] covers, at 21000 msat
        val certificate = part2.array("certificates")[1].jsonObject
        assertEquals(output.str("notePubkey"), certificate.str("notePubkey"))

        CannedService("""{"status":"OK","sig":"${certificate.str("cs1")}"}""").use { service ->
            val outcome = runBlocking {
                LnurlcashClient().rotateWithHash("${service.url}/w/cb", ours, output.str("cp1"))
            }
            assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
            assertEquals(listOf(ours), service.params("k1"))
            assertEquals(output.str("cp1"), service.param("p1"))
            assertNull(service.param("h"))

            // what came back checks against the output alone, as a watcher
            // would, and against the new note's ck1, as its holder would
            val signature = outcome.value.signature!!
            val id = decodeCp1(output.str("cp1"))!!
            assertTrue(verifyNoteSignatureHash(id, 21_000, signature, mintPubkey))
            assertTrue(verifyNoteSignature(output.str("ck1"), 21_000, signature, mintPubkey))
        }
    }

    @Test
    fun `a hash output keeps h, which every mint understands`() {
        val hash = hashK1(k1)
        CannedService("""{"status":"OK","sig":"aa"}""").use { service ->
            val outcome = runBlocking {
                LnurlcashClient().rotateWithHash("${service.url}/w/cb", notes[0].str("ck1"), hash)
            }
            assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
            assertEquals(hash, service.param("h"))
            assertNull(service.param("p1"))
        }
    }

    @Test
    fun `a split names a key and a hash each under its own name`() {
        val cp1 = notes[1].str("cp1")
        val hash = hashK1(k1)
        val body = """{"status":"OK","sig":"aa","sig2":"bb"}"""

        CannedService(body).use { service ->
            val outcome = runBlocking {
                LnurlcashClient().splitWithHash("${service.url}/w/cb", listOf(notes[0].str("ck1")), 5_000, cp1, hash)
            }
            assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
            assertEquals(HashedSplitNotes(signature = "aa", changeSignature = "bb"), outcome.value)
            assertEquals("5000", service.param("amount"))
            assertEquals(cp1, service.param("p1"))
            assertEquals(hash, service.param("h2"))
            assertEquals(null to null, service.param("h") to service.param("p2"))
        }
        // and the other way round
        CannedService(body).use { service ->
            runBlocking {
                LnurlcashClient().splitWithHash("${service.url}/w/cb", listOf(k1), 5_000, hash, cp1)
            }
            assertEquals(hash, service.param("h"))
            assertEquals(cp1, service.param("p2"))
            assertEquals(null to null, service.param("p1") to service.param("h2"))
        }
    }

    @Test
    fun `a merge takes a Part 1 secret and a Part 2 note together`() {
        val ck1 = notes[0].str("ck1")
        val cp1 = notes[2].str("cp1")
        CannedService("""{"status":"OK","sig":"aa"}""").use { service ->
            val outcome = runBlocking {
                LnurlcashClient().mergeWithHash("${service.url}/w/cb", listOf(k1, ck1), cp1)
            }
            assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
            assertEquals(listOf(k1, ck1), service.params("k1"))
            assertEquals(cp1, service.param("p1"))
        }
    }

    @Test
    fun `a ck1 melts like any k1`() {
        val ck1 = notes[0].str("ck1")
        CannedService("""{"status":"OK"}""").use { service ->
            val outcome = runBlocking {
                LnurlcashClient().melt("${service.url}/w/cb", ck1, "lnbc210n1pjqrstuvwxyz")
            }
            assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
            assertEquals(ck1, service.param("k1"))
        }
    }

    @Test
    fun `an output named by the caller leaves nothing to hand back`() {
        // A cp1 output confirmed without its certificate is Unverifiable
        // whatever the options say, and for a generated output that outcome
        // carries the secret. Here the caller named the output, so the only
        // copy of what stands behind it is the one the caller persisted before
        // the call - which is why the KDoc says so.
        CannedService("""{"status":"OK"}""").use { service ->
            val outcome = runBlocking {
                LnurlcashClient().rotateWithHash("${service.url}/w/cb", k1, notes[1].str("cp1"))
            }
            assertTrue(outcome is MutationOutcome.Unverifiable, "got $outcome")
            assertTrue(outcome.newSecrets.isEmpty(), "${outcome.newSecrets}")
        }
        // The tolerant policy preserves a no-signer legacy hash output; strict
        // reference-wallet parity refuses it, still with nothing to hand back.
        val hash = hashK1("22".repeat(32))
        CannedService("""{"status":"OK"}""").use { service ->
            val outcome = runBlocking { LnurlcashClient().rotateWithHash("${service.url}/w/cb", k1, hash) }
            assertTrue(outcome is MutationOutcome.Confirmed, "got $outcome")
            assertNull(outcome.value.signature)
        }
        CannedService("""{"status":"OK"}""").use { service ->
            val outcome = runBlocking {
                LnurlcashClient(requireSignatures = true).rotateWithHash("${service.url}/w/cb", k1, hash)
            }
            assertTrue(outcome is MutationOutcome.Unverifiable, "got $outcome")
            assertTrue(outcome.newSecrets.isEmpty(), "${outcome.newSecrets}")
        }
    }

    // ---- minting to a key ----

    private val invoice = """{"pr":"lnbc210n1pjqrstuvwxyz","disposable":false}"""

    @Test
    fun `a cp1 mints through the comment alone`() {
        val cp1 = notes[0].str("cp1")
        CannedService(invoice).use { service ->
            runBlocking { LnurlcashClient().requestMintInvoiceWithHash("${service.url}/p/cb", 21_000, cp1) }
            assertEquals(cp1, service.param("comment"))
            assertNull(service.param("h"), "h is a hash-only extension")
            assertEquals("21000", service.param("amount"))
        }
        // one spelling of it, whatever the caller was handed
        CannedService(invoice).use { service ->
            runBlocking {
                LnurlcashClient().requestMintInvoiceWithHash("${service.url}/p/cb", 21_000, cp1.uppercase())
            }
            assertEquals(cp1, service.param("comment"))
        }
    }

    @Test
    fun `a hash still mints through both comment and h`() {
        val hash = hashK1(k1)
        CannedService(invoice).use { service ->
            runBlocking { LnurlcashClient().requestMintInvoiceWithHash("${service.url}/p/cb", 21_000, hash) }
            assertEquals(hash, service.param("comment"))
            assertEquals(hash, service.param("h"))
        }
    }

    @Test
    fun `nothing else is asked for an invoice`() {
        val signature = notes[0].str("ownershipSignature")
        for (bad in listOf(notes[0].str("ck1"), encodeCs1WithAmount(21_000, signature), "not-a-32-byte-hash")) {
            CannedService(invoice).use { service ->
                assertFailsWith<LnurlcashException.RequestRefused>(bad) {
                    runBlocking { LnurlcashClient().requestMintInvoiceWithHash("${service.url}/p/cb", 21_000, bad) }
                }
                assertEquals(0, service.requests.size, "$bad: nothing may be sent")
            }
        }
    }

    // ---- ids, lookups and note URLs ----

    @Test
    fun `a Part 1 secret is filed and looked up under its hash`() {
        assertEquals(hashK1(k1), noteIdOf(k1))
        assertEquals(hashK1(k1), noteLookupOf(k1))
    }

    @Test
    fun `nothing else has a note id`() {
        val ck1 = notes[0].str("ck1")
        val corrupted = ck1.dropLast(1) + if (ck1.last() == 'q') 'p' else 'q'
        val cs1Shaped = encodeCs1WithAmount(21_000, notes[0].str("ownershipSignature"))
        for (bad in listOf("", "zz", "11".repeat(31), notes[0].str("cp1"), cs1Shaped, corrupted)) {
            assertNull(noteIdOf(bad), bad)
            assertNull(noteLookupOf(bad), bad)
        }
    }

    @Test
    fun `a Part 2 note is looked up by p and a hash by h`() {
        val cp1 = notes[0].str("cp1")
        val byKey = buildNoteInfoUrlByHash("https://mint.example/w?k1=ab&amount=1", cp1)!!
        assertEquals(cp1, byKey.param("p"))
        assertEquals(null to null, byKey.param("h") to byKey.param("k1"))
        // noteLookupOf is what a caller holding only the ck1 passes in
        assertEquals(
            buildNoteInfoUrlByHash("https://mint.example/w", cp1),
            buildNoteInfoUrlByHash("https://mint.example/w", noteLookupOf(notes[0].str("ck1"))!!),
        )

        val hash = hashK1(k1)
        val byHash = buildNoteInfoUrlByHash("https://mint.example/w", hash)!!
        assertEquals(hash, byHash.param("h"))
        assertNull(byHash.param("p"))

        // the value that spends the note is never a lookup
        assertNull(buildNoteInfoUrlByHash("https://mint.example/w", notes[0].str("ck1")))
    }

    @Test
    fun `a note URL may carry a ck1 but never a cp1`() {
        val url = "https://mint.example/w?k1=${notes[0].str("ck1")}&amount=21000"
        assertEquals(url, resolveNoteInput(url))
        assertEquals(notes[0].str("ck1"), noteK1(url))
        assertNull(resolveNoteInput("https://mint.example/w?k1=${notes[0].str("cp1")}&amount=21000"))
    }
}

/**
 * The same signature with s replaced by n - s and the recovery id's parity bit
 * flipped. Anyone holding a `ck1` can make this, and it recovers to the same
 * key, which is the whole reason notes compare by id.
 */
private fun highSTwin(ck1: String): String {
    val signature = decodeCk1(ck1)!!
    val s = BigInteger(signature.substring(64, 128), 16)
    val twinS = CURVE_ORDER.subtract(s).toString(16).padStart(64, '0')
    val recovery = signature.substring(128).toInt(16) xor 1
    return encodeCk1(signature.substring(0, 64) + twinS + "%02x".format(recovery))
}
