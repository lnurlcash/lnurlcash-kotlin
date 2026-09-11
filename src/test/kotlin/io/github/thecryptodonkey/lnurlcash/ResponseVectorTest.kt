package io.github.thecryptodonkey.lnurlcash

import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.lnurlcash_core.LnurlcashException

/**
 * responses.json, through [LnurlcashClient] against a loopback service that
 * answers each case exactly as it says: a status and a body, a dropped
 * connection, or nothing at all until the client gives up.
 *
 * `op` picks the call, and since 0.10.0 `output` and `change` pick the kind of
 * note it mints: a hash unless the case says cp1, because only a cp1 is owed a
 * certificate. Every case goes through the call that names its outputs, and
 * each case whose outputs are all hashes goes through the call that draws its
 * own secrets as well, so the vectors grade which outcomes carry those secrets
 * out as well as how each is classified. Retries are off, so one case is one
 * request: the replay has tests of its own.
 */
class ResponseVectorTest {

    @BeforeTest
    fun requireVectors() {
        assertTrue(Vectors.available, "conformance vectors not found at ${Vectors.directory}")
    }

    /**
     * Every outcome this test tells apart, and whether it carries the fresh
     * secrets out: the ones that can describe a mutation that landed. A new
     * outcome is a new thing a wallet must do, and fails here until graded.
     */
    private val carries = mapOf(
        "ok" to false,
        "unverifiable" to true,
        "pending" to false,
        "spent" to true,
        "unknown" to true,
        "error" to false,
        "ambiguous" to true,
    )

    private val k1 = "aa".repeat(32)
    private val output = hashK1("bb".repeat(32))
    private val change = hashK1("cc".repeat(32))

    @Test
    fun `every response case, through the client`() {
        val vectors = Vectors.load("responses.json")
        assertEquals(1L, vectors.long("version"))
        assertEquals(carries.keys, vectors.obj("outcomes").keys, "every outcome the file names is graded here")
        val cp1s = Vectors.load("part2.json").array("branches")[0].jsonObject.array("notes")
            .take(2).map { it.jsonObject.str("cp1") }

        val cases = vectors.array("cases").map { it.jsonObject }
        var named = 0
        var drawnGraded = 0
        var cp1Cases = 0
        for (case in cases) {
            val name = case.str("name")
            val op = case.str("op")
            val expected = case.str("expect")
            assertTrue(expected in carries, "$name: expects $expected, which this test does not grade")
            val outputKind = case.strOrNull("output")
            val changeKind = case.strOrNull("change")
            for (kind in listOfNotNull(outputKind, changeKind)) {
                assertEquals("cp1", kind, "$name: an output of a kind this test cannot mint")
            }
            if (outputKind != null || changeKind != null) cp1Cases++
            val first = if (outputKind == "cp1") cp1s[0] else output
            val second = if (changeKind == "cp1") cp1s[1] else change

            // the call that names every output: nothing of this library's rides out
            val namedOutcome = drive(case) { client, callback ->
                when (op) {
                    "melt" -> client.melt(callback, k1, "lnbc210n1pjq")
                    "split" -> client.splitWithHash(callback, listOf(k1), 5_000, first, second)
                    "mutation" -> {
                        assertEquals(null, changeKind, "$name: a rotate has no change")
                        client.rotateWithHash(callback, k1, first)
                    }
                    else -> fail("$name: an op this test does not drive: $op")
                }
            }
            grade(case, "$name (outputs named)", namedOutcome)
            assertEquals(emptyList(), carriedBy(namedOutcome), "$name: carried secrets it never had")
            named++

            if (op == "melt" || outputKind != null || changeKind != null) continue
            // the same answer, to a call that drew its own secrets
            val drawn = mutableListOf<String>()
            val drawingOutcome = drive(case, secretSource = { secret(0x40 + drawn.size).also { drawn.add(it) } }) {
                    client, callback ->
                if (op == "split") client.split(callback, listOf(k1), 5_000) else client.rotate(callback, k1)
            }
            grade(case, "$name (secrets drawn)", drawingOutcome)
            val carried = carriedBy(drawingOutcome)
            if (carries.getValue(expected)) {
                assertEquals(drawn, carried, "$name: a $expected outcome carries every drawn secret, in output order")
            } else {
                assertTrue(carried.isEmpty(), "$name: a $expected outcome carried $carried")
            }
            drawnGraded++
        }
        assertEquals(cases.size, named, "every case graded")
        assertTrue(cp1Cases >= 3, "0.10.0 carries three cp1 cases, found $cp1Cases")
        assertTrue(drawnGraded > 10, "too few plain cases driven through the drawing calls: $drawnGraded")
    }

    /** One case's answer, served once to one call. */
    private fun drive(
        case: JsonObject,
        secretSource: () -> String = ::generateNoteSecret,
        call: suspend (client: LnurlcashClient, callback: String) -> MutationOutcome<*>,
    ): MutationOutcome<*> {
        val answer = when {
            case.flag("transportError") -> CannedService.Answer.Drop
            case.flag("timeout") -> CannedService.Answer.Stall
            else -> CannedService.Answer.Json(
                body = case["body"]?.takeIf { it !is JsonNull }?.toString() ?: case.str("bodyRaw"),
                status = case.long("http").toInt(),
            )
        }
        val timeout = if (case.flag("timeout")) Duration.ofMillis(300) else Duration.ofSeconds(10)
        return CannedService(answer).use { service ->
            val client = LnurlcashClient(timeout = timeout, secretSource = secretSource, mutationRetries = 0)
            runBlocking { call(client, "${service.url}/w/cb") }
        }
    }

    private fun grade(case: JsonObject, at: String, outcome: MutationOutcome<*>) {
        assertEquals(case.str("expect"), outcomeOf(outcome), "$at: $outcome - ${case.strOrNull("why") ?: ""}")
        if (outcome !is MutationOutcome.Confirmed) return
        when (val value = outcome.value) {
            is MeltReceipt -> {
                val body = case.obj("body")
                assertEquals(body.strOrNull("pr"), value.invoice, "$at: melt proof")
                assertEquals(body.strOrNull("verify"), value.verifyUrl, "$at: melt proof")
            }
            else -> {
                val (signature, changeSignature) = signaturesOf(value)
                assertEquals(case.strOrNull("signature"), signature, "$at: signature")
                assertEquals(case.strOrNull("changeSignature"), changeSignature, "$at: change signature")
            }
        }
    }

    /** An outcome in responses.json's own words. */
    private fun outcomeOf(outcome: MutationOutcome<*>): String = when (outcome) {
        is MutationOutcome.Confirmed -> "ok"
        is MutationOutcome.Unverifiable -> "unverifiable"
        is MutationOutcome.Unknown -> "ambiguous"
        is MutationOutcome.Rejected -> when (outcome.error) {
            is LnurlcashException.NotePending -> "pending"
            is LnurlcashException.NoteSpent -> "spent"
            is LnurlcashException.NoteUnknown -> "unknown"
            is LnurlcashException.ServiceRejected -> "error"
            else -> "unclassified (${outcome.error})"
        }
    }

    /** The fresh secrets an outcome hands back, wherever it keeps them. */
    private fun carriedBy(outcome: MutationOutcome<*>): List<String> = when (outcome) {
        is MutationOutcome.Unknown -> outcome.newSecrets
        is MutationOutcome.Unverifiable -> outcome.newSecrets
        is MutationOutcome.Rejected -> when (val error = outcome.error) {
            is LnurlcashException.NoteSpent -> error.newSecrets
            is LnurlcashException.NoteUnknown -> error.newSecrets
            else -> emptyList()
        }
        is MutationOutcome.Confirmed -> emptyList()
    }

    private fun signaturesOf(value: Any?): Pair<String?, String?> = when (value) {
        is RotatedNote -> value.signature to null
        is SplitNotes -> value.signature to value.changeSignature
        is HashedNote -> value.signature to null
        is HashedSplitNotes -> value.signature to value.changeSignature
        else -> fail("a result this test does not know: $value")
    }

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true
}
