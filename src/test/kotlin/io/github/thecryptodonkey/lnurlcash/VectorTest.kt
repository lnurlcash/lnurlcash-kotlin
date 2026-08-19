package io.github.thecryptodonkey.lnurlcash

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every assertion here comes from lnurlcash-conformance, the same files the
 * TypeScript, Python, Rust and Go implementations are held to. A disagreement
 * means one of the two is wrong, which is the entire point of keeping them
 * separate.
 */
class VectorTest {

    @BeforeTest
    fun requireVectors() {
        assertTrue(
            Vectors.available,
            "conformance vectors not found at ${Vectors.directory} - " +
                "check out lnurlcash-conformance alongside this repo, or set LNURLCASH_CONFORMANCE",
        )
    }

    @Test
    fun `signature vectors`() {
        val cases = Vectors.cases("signature.json", "cases")
        assertTrue(cases.size > 5, "too few signature cases to be meaningful")
        for (case in cases) {
            assertEquals(
                case.bool("valid"),
                verifyNoteSignature(
                    case.str("k1"),
                    case.long("amountMsat"),
                    case.str("signature"),
                    case.str("mintPubkey"),
                ),
                case.str("name"),
            )
        }
    }

    @Test
    fun `url admission vectors`() {
        for (url in Vectors.load("url-admission.json").array("allowed")) {
            assertTrue(isAllowedServiceUrl(url.jsonPrimitive.content), "should allow $url")
        }
        for (case in Vectors.cases("url-admission.json", "rejected")) {
            assertTrue(
                !isAllowedServiceUrl(case.str("url")),
                "should reject ${case.str("url")} - ${case.str("why")}",
            )
        }
    }

    @Test
    fun `input resolution vectors`() {
        for (case in Vectors.cases("input-resolution.json", "lnurl")) {
            assertEquals(case.strOrNull("expect"), resolveLnurlInput(case.str("input")))
        }
        for (case in Vectors.cases("input-resolution.json", "mint")) {
            assertEquals(case.strOrNull("expect"), resolveMintInput(case.str("input")))
        }
        for (case in Vectors.cases("input-resolution.json", "note")) {
            assertEquals(case.strOrNull("expect"), resolveNoteInput(case.str("input")))
        }
        for (case in Vectors.cases("input-resolution.json", "mintAddressUrl")) {
            assertEquals(case.strOrNull("expect"), mintAddressUrl(case.str("payUrl")))
        }
    }

    @Test
    fun `note url vectors`() {
        for (case in Vectors.cases("note-url.json", "parse")) {
            val url = case.str("url")
            assertEquals(case.strOrNull("k1"), noteK1(url), "k1 of $url")
            assertEquals(
                case.longOrNull("declaredAmountMsat"),
                noteDeclaredAmountMsat(url),
                "declared amount of $url",
            )
            assertEquals(case.strOrNull("signature"), noteSignature(url), "signature of $url")
        }
        for (case in Vectors.cases("note-url.json", "build")) {
            assertEquals(
                case.str("expect"),
                buildNoteUrl(case.str("withdrawLink"), case.str("k1"), case.longOrNull("amountMsat")),
            )
        }
        for (case in Vectors.cases("note-url.json", "withNewK1")) {
            assertEquals(
                case.str("expect"),
                withNewK1(
                    case.str("url"),
                    case.str("k1"),
                    case.long("amountMsat"),
                    case.strOrNull("signature"),
                ),
            )
        }
        for (case in Vectors.cases("note-url.json", "withoutK1")) {
            assertEquals(
                case.str("expect"),
                withoutK1(case.str("url"), case.long("amountMsat"), case.strOrNull("signature")),
            )
        }
    }

    @Test
    fun `fee vectors`() {
        for (case in Vectors.cases("fees.json", "parse")) {
            val parsed = parseMintFee(case.str("metadata"))
            if (case["expect"] is JsonNull || case["expect"] == null) {
                assertNull(parsed, "metadata ${case.str("metadata")}")
            } else {
                assertEquals(case["expect"]!!.mintFee(), parsed, "metadata ${case.str("metadata")}")
            }
        }
        for (case in Vectors.cases("fees.json", "apply")) {
            assertEquals(
                case.long("expect"),
                applyMintFee(case.long("grossMsat"), case["fee"]!!.mintFee()),
                "apply to ${case.long("grossMsat")}",
            )
        }
        for (case in Vectors.cases("fees.json", "grossUp")) {
            assertEquals(
                case.long("expect"),
                grossUpForMintFee(case.long("netMsat"), case["fee"]!!.mintFee()),
                "gross up ${case.long("netMsat")}",
            )
        }
    }

    @Test
    fun `gross up is always the true minimum`() {
        val spec = Vectors.load("fees.json").obj("grossUpRoundTrip")
        for (rawFee in spec.array("fees")) {
            val fee = rawFee.mintFee()
            for (rawNet in spec.array("netAmountsMsat")) {
                val net = rawNet.jsonPrimitive.content.toLong()
                val gross = grossUpForMintFee(net, fee)
                assertEquals(net, applyMintFee(gross, fee), "$net through $fee")
                assertTrue(applyMintFee(gross - 1, fee) < net, "$net through $fee: not minimal")
            }
        }
    }

    @Test
    fun `bolt11 vectors`() {
        for (case in Vectors.cases("bolt11.json", "decodeAmountMsat")) {
            assertEquals(
                case.longOrNull("expect"),
                decodeBolt11AmountMsat(case.str("pr")),
                "amount of ${case.str("pr")}",
            )
        }
        for (case in Vectors.cases("bolt11.json", "isInvoice")) {
            assertEquals(
                case.bool("expect"),
                isBolt11Invoice(case.str("pr")),
                "shape of ${case.str("pr")}",
            )
        }
        for (case in Vectors.cases("bolt11.json", "sameInvoice")) {
            assertEquals(case.bool("expect"), sameInvoice(case.str("a"), case.str("b")))
        }
        for (case in Vectors.cases("bolt11.json", "isPreimage")) {
            assertEquals(
                case.bool("expect"),
                isPreimage(case.str("value")),
                "preimage shape of ${case.str("value")}",
            )
        }
    }

    @Test
    fun `amounts survive the crossing as Long`() {
        // the bindings speak ULong; this wrapper speaks Long. 21M BTC in msat is
        // the largest amount that can exist, and it must round-trip exactly.
        val allBitcoin = 2_100_000_000_000_000L
        val fee = MintFee(baseFeeMsat = 3, feePpm = 999_999)
        assertEquals(2_099_999_997L, applyMintFee(allBitcoin, fee))
        assertEquals(3_000_001L, grossUpForMintFee(1, fee))
    }
}
