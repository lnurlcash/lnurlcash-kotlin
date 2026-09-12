package io.github.thecryptodonkey.lnurlcash

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * LUD-25 Part 2, from part2.json and nostr-seed.json, graded through the
 * Kotlin facade rather than the Rust directly.
 *
 * The core already grades itself against these files. What this adds is the
 * FFI boundary: every key, node and signature crosses it as hex, a `Cx1` comes
 * back as a record, and an index crosses as a `UInt` whose top bit is where a
 * signed-integer mistake would show. None of that is visible from either side
 * alone.
 *
 * The few checks the facade has no function for (the BIP-39 seed, the digests,
 * the parity a branch key's negation depends on) are computed here with the
 * JDK and then tied back to something the facade produced, so a vector
 * regenerated under a different convention fails as that convention rather
 * than as a byte mismatch three levels down.
 */
class Part2VectorTest {

    @BeforeTest
    fun requireVectors() {
        assertTrue(
            Vectors.available,
            "conformance vectors not found at ${Vectors.directory} - " +
                "check out lnurlcash-conformance alongside this repo, or set LNURLCASH_CONFORMANCE",
        )
    }

    private val part2: JsonObject by lazy { Vectors.load("part2.json") }

    @Test
    fun `names the conventions it implements`() {
        val conventions = part2.obj("conventions")
        assertEquals("m/139'/1'/d1/d2/d3/d4", conventions.str("addressBranch"))
        assertEquals("m/139'/1'/0", conventions.str("hashingKey"))
        assertEquals("LNURLcash", conventions.str("ownershipMessage"))
        assertEquals("LNURLcash:<amount_msat>:<hex(pk)>", conventions.str("certificateMessage"))
        // The digest every ownership signature is made over. The facade has no
        // function for it; that every signature below comes out byte for byte
        // is what ties signNoteOwnership to it, since RFC6979 draws the nonce
        // from the digest.
        assertEquals(
            conventions.str("ownershipDigest"),
            lightningSignedDigest(conventions.str("ownershipMessage")).hex(),
        )
    }

    @Test
    fun `every branch and every note`() {
        val branches = part2.array("branches").map { it.jsonObject }
        assertEquals(8, branches.size, "part2.json 0.9.0 has eight branches")
        // An odd branch is the only thing that exercises the negation, and the
        // top of the u32 range is where a hardened-index or signed-integer
        // mistake at the boundary would show.
        assertTrue(branches.any { it.str("branchParity") == "odd" })
        assertTrue(branches.any { it.str("branchParity") == "even" })
        val indices = branches[0].array("notes").map { it.jsonObject.long("index") }
        assertTrue(0x8000_0000L in indices && 0xFFFF_FFFFL in indices, "indices $indices")

        var graded = 0
        for (branch in branches) {
            val host = branch.str("host")
            val seedHex = branch.str("seedHex")
            assertEquals(seedHex, bip39Seed(branch.str("mnemonic")), "$host: mnemonic")

            val root = deriveCashRoot(seedHex)
            assertEquals(branch.str("cashRoot"), root, host)
            // m/139' is one hardened step below the master, whichever way it is reached
            assertEquals(root, deriveCashChild(deriveCashMaster(seedHex), 0x8000_008Bu), host)

            // the hashing key is m/139'/1'/0, so the four levels hang off m/139'/1'
            val purpose = deriveCashChild(root, 0x8000_0001u)
            val domainIndices = branch.array("domainIndices").map { it.jsonPrimitive.long.toUInt() }
            assertEquals(domainIndices, cashDomainIndices(purpose, host), host)

            val node = deriveCashAddressNode(root, host)
            assertEquals(branch.str("addressNode"), node, host)
            // and it is exactly those four raw levels, walked one CKDpriv at a
            // time, each hardened or not by its own top bit
            assertEquals(node, domainIndices.fold(purpose) { at, index -> deriveCashChild(at, index) }, host)
            val privateKey = node.substring(0, 64)
            val chainCode = node.substring(64)

            val cx1 = cashNodeToCx1(node)
            assertEquals(branch.str("branchPubkey"), cx1.pubkeyXOnly, host)
            assertEquals(branch.str("chainCode"), cx1.chainCode, host)
            assertEquals(chainCode, cx1.chainCode, host)
            val cx1String = branch.str("cx1")
            assertEquals(cx1String, encodeCx1(cx1.pubkeyXOnly, cx1.chainCode), host)
            assertTrue(isCx1(cx1String), host)
            // a watcher starts from the string, never the node
            val watched = decodeCx1(cx1String)
            assertEquals(cx1, watched, host)
            assertNotNull(watched)

            val odd = when (val parity = branch.str("branchParity")) {
                "odd" -> true
                "even" -> false
                else -> error("$host: a parity that is neither: $parity")
            }

            for (note in branch.array("notes").map { it.jsonObject }) {
                val index = note.long("index").toUInt()
                val at = "$host #$index"

                val pubkey = deriveNotePubkey(watched.pubkeyXOnly, watched.chainCode, index)
                assertEquals(note.str("notePubkey"), pubkey, at)
                val cp1 = note.str("cp1")
                assertEquals(cp1, encodeCp1(pubkey), at)
                assertEquals(pubkey, decodeCp1(cp1), at)
                assertTrue(isCp1(cp1), at)

                val secretKey = deriveNoteSecretKey(privateKey, chainCode, index)
                assertEquals(note.str("noteSecretKey"), secretKey, at)
                // Compile real Java calls and grade both long overloads against
                // every vector, including indices above Integer.MAX_VALUE.
                NoteDerivationJavaTest.assertDerivation(
                    watched.pubkeyXOnly, privateKey, chainCode, note.long("index"),
                    note.str("notePubkey"), note.str("noteSecretKey"),
                )
                // branchParity, graded against the facade's own answer: the
                // holder's key is (p or n - p) + t, and which one it is is
                // exactly what the parity says
                assertEquals(
                    tweakedSecretKey(privateKey, odd, noteTweak(cx1.pubkeyXOnly, cx1.chainCode, index)),
                    secretKey,
                    "$at: branchParity",
                )

                // RFC6979: the same key reproduces the same ck1, byte for byte
                val signature = signNoteOwnership(secretKey)
                assertEquals(note.str("ownershipSignature"), signature, at)
                assertTrue(signature.substring(128).toInt(16) <= 3, "$at: recovery id")
                assertTrue(isLowS(signature), "$at: high S")
                val ck1 = note.str("ck1")
                assertEquals(ck1, encodeCk1(signature), at)
                assertEquals(signature, decodeCk1(ck1), at)
                assertTrue(isCk1(ck1), at)

                // the service's side: the ck1 alone gives the key the note is
                // filed under, which is also the one the watcher derived
                assertEquals(pubkey, recoverNoteOwnershipPubkey(signature), at)
                assertEquals(pubkey, noteIdOf(ck1), at)
                assertEquals(cp1, noteLookupOf(ck1), at)
                graded++
            }
        }
        assertEquals(56, graded, "eight branches of seven notes")
    }

    @Test
    fun `every certificate recovers to the mint and to nothing else`() {
        val mint = part2.obj("mint")
        val mintPubkey = mint.str("mintPubkey")
        // The mint's key pair, through the facade: its x-only key is what an
        // ownership signature by it recovers to. The prefix byte is graded by
        // every verification below, which compares the full compressed key.
        assertEquals(
            mintPubkey.substring(2),
            recoverNoteOwnershipPubkey(signNoteOwnership(mint.str("privateKey"))),
        )

        // Every note in the file by key, so each certificate is checked the
        // way a recipient checks one: from the note's ck1 and nothing else.
        val ck1Of = part2.array("branches")
            .flatMap { it.jsonObject.array("notes") }
            .associate { it.jsonObject.str("notePubkey") to it.jsonObject.str("ck1") }

        val certificates = part2.array("certificates").map { it.jsonObject }
        assertEquals(4, certificates.size)
        for (certificate in certificates) {
            val pubkey = certificate.str("notePubkey")
            val amount = certificate.long("amountMsat")
            val at = "$amount msat"
            val message = certificate.str("message")
            val digest = certificate.str("digest")
            val signature = certificate.str("signature")
            val cs1 = certificate.str("cs1")

            // The message names the note by its key, and that key is the one
            // the note's ck1 recovers to. The digest is the message as a
            // Lightning node signs it. Verification below only succeeds if the
            // facade computes that same digest, so the two are graded together.
            assertEquals("LNURLcash:$amount:$pubkey", message, at)
            assertEquals(digest, lightningSignedDigest(message).hex(), at)
            val ck1 = ck1Of[pubkey] ?: error("$at: the certificate names a note in the file")
            assertEquals(pubkey, noteIdOf(ck1), at)

            assertEquals(cs1, encodeCs1(signature), at)
            assertEquals(signature, decodeCs1(cs1), at)
            assertTrue(isCs1(cs1), at)
            // a certificate and a spend share a layout but never a prefix
            assertFalse(isCk1(cs1), at)
            assertFalse(isCs1(ck1), at)

            // Each recovers to the mint's key: by the note's key, in either
            // spelling of the signature, and from the ck1 alone...
            assertTrue(verifyNoteSignatureHash(pubkey, amount, signature, mintPubkey), at)
            assertTrue(verifyNoteSignatureHash(pubkey, amount, cs1, mintPubkey), at)
            assertTrue(verifyNoteSignature(ck1, amount, cs1, mintPubkey), at)
            assertTrue(verifyNoteSignature(ck1, amount, signature, mintPubkey), at)
            // ...and to nothing it does not cover
            assertFalse(verifyNoteSignature(ck1, amount + 1, cs1, mintPubkey), "$at: another amount")
            val other = ck1Of.entries.first { it.key != pubkey }.value
            assertFalse(verifyNoteSignature(other, amount, cs1, mintPubkey), "$at: another note")
            val otherParity = (if (mintPubkey.startsWith("02")) "03" else "02") + mintPubkey.substring(2)
            assertFalse(verifyNoteSignatureHash(pubkey, amount, cs1, otherParity), "$at: the key's other parity")
        }
    }

    @Test
    fun `valid and invalid strings`() {
        // the payload as hex, or null, for each of the four types
        fun decode(type: String, value: String): String? {
            val (decoded, claims) = when (type) {
                "cp1" -> decodeCp1(value) to isCp1(value)
                "ck1" -> decodeCk1(value) to isCk1(value)
                "cs1" -> decodeCs1(value) to isCs1(value)
                "cx1" -> decodeCx1(value)?.let { it.pubkeyXOnly + it.chainCode } to isCx1(value)
                else -> error("a string type this library does not know: $type")
            }
            assertEquals(decoded != null, claims, "$type $value: is and decode disagree")
            return decoded
        }

        val valid = part2.array("valid").map { it.jsonObject }
        assertTrue(valid.isNotEmpty())
        for (case in valid) {
            assertEquals(case.str("bytes"), decode(case.str("type"), case.str("value")), case.str("why"))
        }
        val invalid = part2.array("invalid").map { it.jsonObject }
        assertTrue(invalid.isNotEmpty())
        for (case in invalid) {
            val why = case.str("why")
            assertTrue(why.isNotEmpty(), "an invalid string without a reason")
            assertNull(decode(case.str("type"), case.str("value")), why)
        }
    }

    /** An extension, not LUD-25: a Part 2 branch rooted in a Nostr identity key. */
    @Test
    fun `a branch rooted in a Nostr key`() {
        val vectors = Vectors.load("nostr-seed.json")
        assertTrue(vectors.bool("extension"))
        val label = vectors.str("label")
        assertEquals("LNURLcash/nostr-seed", label)

        val cases = vectors.array("cases").map { it.jsonObject }
        assertEquals(4, cases.size)
        var graded = 0
        for (case in cases) {
            val host = case.str("host")
            val identity = case.str("identity")

            val seed = deriveNostrCashSeed(identity)
            assertEquals(case.str("seed"), seed, host)
            // the label is the whole of the construction
            assertEquals(seed, hmacSha256(identity.unhex(), label.toByteArray()).hex(), host)
            // the x-only key a lightning address on this branch belongs to
            assertEquals(case.str("identityPubkey"), recoverNoteOwnershipPubkey(signNoteOwnership(identity)), host)

            val node = deriveNostrAddressNode(identity, host)
            assertEquals(case.str("addressNode"), node, host)
            // nothing new past the seed: the ordinary address path from it
            assertEquals(node, deriveCashAddressNode(deriveCashRoot(seed), host), host)

            val cx1 = cashNodeToCx1(node)
            assertEquals(case.str("cx1"), encodeCx1(cx1.pubkeyXOnly, cx1.chainCode), host)
            assertEquals(cx1, decodeCx1(case.str("cx1")), host)

            for (note in case.array("notes").map { it.jsonObject }) {
                val index = note.long("index").toUInt()
                val at = "$host #$index"
                val secretKey = deriveNoteSecretKey(node.substring(0, 64), node.substring(64), index)
                assertEquals(note.str("noteSecretKey"), secretKey, at)
                val pubkey = deriveNotePubkey(cx1.pubkeyXOnly, cx1.chainCode, index)
                assertEquals(note.str("notePubkey"), pubkey, at)
                assertEquals(note.str("cp1"), encodeCp1(pubkey), at)
                val ck1 = encodeCk1(signNoteOwnership(secretKey))
                assertEquals(note.str("ck1"), ck1, at)
                assertEquals(pubkey, noteIdOf(note.str("ck1")), "$at: ck1 recovery")
                assertEquals(note.str("cp1"), noteLookupOf(ck1), at)
                graded++
            }
        }
        assertEquals(12, graded, "four identities of three notes")
    }
}

// ---- the arithmetic the facade has no function for ----

internal val CURVE_ORDER: BigInteger =
    BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(message)
    }

/** What a Lightning node's signmessage signs, and so every LNURLcash signature. */
private fun lightningSignedDigest(message: String): ByteArray =
    sha256(sha256("Lightning Signed Message:".toByteArray(), message.toByteArray()))

/**
 * BIP-39's seed from its mnemonic: PBKDF2-HMAC-SHA512, 2048 rounds, salt
 * "mnemonic" and no passphrase. Only here so the vectors' mnemonic is graded
 * against their seedHex; the library takes raw seed bytes and carries no
 * wordlist.
 */
private fun bip39Seed(mnemonic: String): String =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        .generateSecret(PBEKeySpec(mnemonic.toCharArray(), "mnemonic".toByteArray(), 2048, 512))
        .encoded
        .hex()

/** t = tagged_hash("LNURLcash/derive", P || chainCode || ser32_be(i)) */
private fun noteTweak(pubkeyXOnly: String, chainCode: String, index: UInt): BigInteger {
    val tag = sha256("LNURLcash/derive".toByteArray())
    val i = index.toInt()
    val be = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())
    return BigInteger(1, sha256(tag, tag, pubkeyXOnly.unhex(), chainCode.unhex(), be))
}

/** sk_i = ((P has even y ? p : n - p) + t) mod n */
private fun tweakedSecretKey(privateKey: String, odd: Boolean, tweak: BigInteger): String {
    val p = BigInteger(privateKey, 16)
    val base = if (odd) CURVE_ORDER.subtract(p) else p
    return base.add(tweak).mod(CURVE_ORDER).toString(16).padStart(64, '0')
}

/** s at or below n/2, which is what libsecp256k1 always produces. */
private fun isLowS(signatureHex: String): Boolean =
    BigInteger(signatureHex.substring(64, 128), 16) <= CURVE_ORDER.shiftRight(1)
