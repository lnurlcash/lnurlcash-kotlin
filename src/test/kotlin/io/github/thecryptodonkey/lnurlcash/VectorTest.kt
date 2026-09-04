package io.github.thecryptodonkey.lnurlcash

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.lnurlcash_core.mintInvoiceRequest
import uniffi.lnurlcash_core.mintInvoiceRequestWithHash
import uniffi.lnurlcash_core.parseInvoice
import uniffi.lnurlcash_core.parsePayRequest
import uniffi.lnurlcash_core.parseVerify

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

    /**
     * LUD-25 minting, from pay-request.json.
     *
     * This is the suite that would have caught the library sitting on the
     * deleted preimage-keyed model for a month: nothing here states an opinion
     * of its own, so a draft change lands as a red test rather than as a silent
     * divergence discovered by a wallet that could not mint.
     */
    @Test
    fun `pay request vectors`() {
        val vectors = Vectors.load("pay-request.json")

        for (case in vectors.array("accepted").map { it.jsonObject }) {
            val name = case.str("name")
            val info = parsePayRequest(case.obj("body").toString())
            assertEquals(case.strOrNull("withdrawLink"), info.withdrawLink, name)
            assertEquals(case.longOrNull("commentAllowed"), info.commentAllowed?.toLong(), name)
            val expectedFee = case["mintFee"]?.takeIf { it !is JsonNull }?.mintFee()
            assertEquals(expectedFee?.baseFeeMsat, info.mintFee?.baseFeeMsat?.toLong(), name)
            assertEquals(expectedFee?.feePpm, info.mintFee?.feePpm?.toLong(), name)
            // A payRequest is only a mint if it can carry the commitment, and a
            // mint is only a mint if it advertises where the note will live.
            assertEquals(info.withdrawLink != null, info.namesMintOutput, name)
        }

        for (case in vectors.array("rejected").map { it.jsonObject }) {
            val name = case.str("name")
            assertTrue(
                runCatching { parsePayRequest(case.obj("body").toString()) }.isFailure,
                "$name: must not parse",
            )
        }

        // The mint callback names the note before the invoice exists.
        val callback = "https://mint.example/p/cb"
        val mintCallback = vectors.obj("mintCallback")
        for (case in mintCallback.array("accepted").map { it.jsonObject }) {
            val name = case.str("name")
            val comment = case.str("comment")
            val amount = case.long("amountMsat")
            val request = mintInvoiceRequestWithHash(callback, amount.toULong(), comment)
            // LUD-25 carries the commitment as a mandatory LUD-12 comment; h
            // repeats it for the additive ForgeSworn profile.
            assertTrue(request.url.contains("comment=$comment"), "$name: ${request.url}")
            assertTrue(request.url.contains("h=$comment"), name)
            assertTrue(request.url.contains("amount=$amount"), name)
            assertEquals(comment, case.str("noteId"), name)
            assertEquals(false, case.bool("paymentPreimageIsBearerK1"), name)
        }
        for (case in mintCallback.array("rejected").map { it.jsonObject }) {
            val name = case.str("name")
            val amount = case.long("amountMsat")
            val comment = case.strOrNull("comment")
            // A null comment is the unnamed mint the draft forbids: this
            // library cannot express one, because the minting builder requires
            // the commitment. A malformed one is refused before it is sent.
            val attempt = runCatching {
                if (comment == null) {
                    mintInvoiceRequest(callback, amount.toULong(), "")
                } else {
                    mintInvoiceRequestWithHash(callback, amount.toULong(), comment)
                }
            }
            assertTrue(attempt.isFailure, "$name: must be refused before anything is sent")
        }

        val invoice = vectors.obj("invoice")
        for (case in invoice.array("accepted").map { it.jsonObject }) {
            val name = case.str("name")
            val parsed = parseInvoice(case.obj("body").toString(), case.long("requestedMsat").toULong())
            assertEquals(case.bool("disposable"), parsed.disposable, name)
            assertEquals(case.strOrNull("verify"), parsed.verify, name)
        }
        for (case in invoice.array("rejected").map { it.jsonObject }) {
            val name = case.str("name")
            assertTrue(
                runCatching {
                    parseInvoice(case.obj("body").toString(), case.long("requestedMsat").toULong())
                }.isFailure,
                "$name: must not parse",
            )
        }

        val verify = vectors.obj("verify")
        for (case in verify.array("accepted").map { it.jsonObject }) {
            val name = case.str("name")
            val parsed = parseVerify(case.obj("body").toString())
            assertEquals(case.bool("settled"), parsed.settled, name)
            assertEquals(case.strOrNull("preimage"), parsed.preimage, name)
        }
        for (case in verify.array("rejected").map { it.jsonObject }) {
            val name = case.str("name")
            assertTrue(
                runCatching { parseVerify(case.obj("body").toString()) }.isFailure,
                "$name: must not parse",
            )
        }
    }
}

/**
 * The derivation vectors, driven through the Kotlin facade rather than the
 * Rust directly. The core already grades itself against these files; what this
 * adds is the FFI boundary, where every value crosses as hex and a marshalling
 * mistake would be invisible from either side alone.
 */
class DerivationVectorTest {
    @Test
    fun `derives LUD-25 note secrets`() {
        val vectors = Vectors.load("cash-derivation.json")
        assertEquals(
            "m/139'/d1/d2/d3/d4/i'",
            vectors["scheme"]!!.jsonObject["secretPath"]!!.jsonPrimitive.content,
        )
        for (case in Vectors.cases("cash-derivation.json", "cases")) {
            val name = case["name"]!!.jsonPrimitive.content
            val host = case["host"]!!.jsonPrimitive.content
            val index = case["index"]!!.jsonPrimitive.content.toUInt()
            val root = deriveCashRoot(case["seedHex"]!!.jsonPrimitive.content)
            assertEquals(case["cashRoot"]!!.jsonPrimitive.content, root, name)

            val domainNode = deriveCashDomainNode(root, host)
            assertEquals(case["domainNode"]!!.jsonPrimitive.content, domainNode, name)

            val k1 = case["k1"]!!.jsonPrimitive.content
            assertEquals(k1, deriveCashSecret(root, host, index), name)
            // The hardware-signer path: the mint's subtree alone resolves it.
            assertEquals(k1, cashSecretAt(domainNode, index), name)
            assertEquals(case["noteId"]!!.jsonPrimitive.content, hashK1(k1), name)
        }
    }

    @Test
    fun `derives the legacy scheme too, so old notes stay findable`() {
        for (case in Vectors.cases("derivation.json", "cases")) {
            val name = case["name"]!!.jsonPrimitive.content
            val root = deriveNoteRoot(case["seedHex"]!!.jsonPrimitive.content)
            val k1 = deriveNoteSecret(
                root,
                case["host"]!!.jsonPrimitive.content,
                case["index"]!!.jsonPrimitive.content.toUInt(),
            )
            assertEquals(case["k1"]!!.jsonPrimitive.content, k1, name)
        }
    }

    @Test
    fun `builds the private hash lookup a restore walk needs`() {
        val h = "ab".repeat(32)
        assertEquals(
            "https://mint.example/w?h=$h",
            buildNoteInfoUrlByHash("https://mint.example/w?k1=aa&amount=1", h),
        )
        assertNull(buildNoteInfoUrlByHash("https://mint.example/w", "not-a-hash"))
    }
}
