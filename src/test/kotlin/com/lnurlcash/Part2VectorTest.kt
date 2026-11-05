package com.lnurlcash

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * LUD-26 and the key-path notes beneath it, from part2.json and
 * nostr-seed.json, graded through the Kotlin facade rather than the Rust
 * directly.
 *
 * The core already grades itself against these files. What this adds is the
 * FFI boundary: every key, node and signature crosses it as hex, a note path
 * crosses as a sealed class, a `Cx1` comes back as a record, and an index
 * crosses as a `UInt` whose top bit is where a signed-integer mistake would
 * show. None of that is visible from either side alone.
 *
 * The few checks the facade has no function for (the BIP-39 seed, the digests)
 * are computed here with the JDK and then tied back to something the facade
 * produced, so a vector regenerated under a different convention fails as that
 * convention rather than as a byte mismatch three levels down.
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
        assertEquals("m/139'/d1/d2/d3/d4", conventions.str("serviceRoot"))
        assertEquals("m/139'/0", conventions.str("hashingKey"))
        assertEquals("S/a'/0/i", conventions.str("walletChain"))
        assertEquals("S/a'/1/i", conventions.str("changeChain"))
        assertEquals("S/a'/2/n'/i", conventions.str("shareNote"))
        assertEquals("LNURLcash:<domain>:<amount_msat>:<hex(Q)>", conventions.str("certificateMessage"))
        assertEquals("cs || BOLT11_amount_suffix(amount_msat)", conventions.str("certificateHrp"))
        assertEquals("chainCode (32) || P (33, compressed), BIP-32 order, 113 characters", conventions.str("cx1Payload"))
    }

    @Test
    fun `every service root, account and note`() {
        val branches = part2.array("branches").map { it.jsonObject }
        assertEquals(8, branches.size, "part2.json has eight branches: four hosts, two seeds")
        var graded = 0
        for (branch in branches) {
            val host = branch.str("host")
            val domain = branch.str("domain")
            val seedHex = branch.str("seedHex")
            assertEquals(seedHex, bip39Seed(branch.str("mnemonic")), host)

            val root = deriveCashRoot(seedHex)
            // m/139' is one hardened step below the master, whichever way it is reached
            assertEquals(root, deriveCashChild(deriveCashMaster(seedHex), 0x8000_008Bu), host)
            // the hashing key is m/139'/0, so the four levels hang off the root itself
            assertEquals(branch.str("cashHashingKey"), deriveCashChild(root, 0u).substring(0, 64), host)
            val domainIndices = branch.array("domainIndices").map { it.jsonPrimitive.long.toUInt() }
            assertEquals(domainIndices, cashDomainIndices(root, domain), host)

            // The host is as stored, port included; the root is always the bare hostname's.
            val serviceRoot = deriveServiceRoot(root, host)
            assertEquals(branch.str("serviceRoot"), serviceRoot, host)
            assertEquals(serviceRoot, deriveServiceRoot(root, "https://${host.uppercase()}/path"), host)
            assertEquals(serviceRoot, deriveCashDomainNode(root, domain), host)
            // and it is exactly those four raw levels, walked one CKDpriv at a time,
            // each hardened or not by its own top bit
            assertEquals(serviceRoot, domainIndices.fold(root) { at, index -> deriveCashChild(at, index) }, host)
            if (host != domain) {
                assertNotEquals(serviceRoot, deriveCashDomainNode(root, host), "$host: a port is part of the string hashed")
            }
            graded += gradeAccounts(serviceRoot, domain, branch.array("accounts"), host)
        }
        // eight branches, two accounts each: account 0 holds 5 wallet, 2 change and 2 shares of
        // 4 notes, account 1 one of each
        assertTrue(graded >= 8 * 5, "graded only $graded notes")
    }

    @Test
    fun `every certificate recovers to the mint and to nothing else`() {
        val mint = part2.obj("mint")
        val mintPubkey = mint.str("mintPubkey")
        // the mint's key pair, through the facade: its x-only key is what the
        // prefix-less pubkey is, and what a signature by it recovers to
        val first = part2.array("branches")[0].jsonObject
        assertEquals("mint.example", first.str("domain"))
        val mintDomain = first.str("domain")
        val ck1Of = first.array("accounts")[0].jsonObject.array("wallet")
            .associate { it.jsonObject.str("notePubkey") to it.jsonObject.str("ck1") }

        val certificates = part2.array("certificates").map { it.jsonObject }
        assertEquals(5, certificates.size)
        for (certificate in certificates) {
            val pubkey = certificate.str("notePubkey")
            val domain = certificate.str("domain")
            val amount = certificate.long("amountMsat")
            val at = "$domain $amount msat"
            val message = certificate.str("message")
            val signature = certificate.str("signature")
            val cs1 = certificate.str("cs1")

            // the message names the mint and the note's output key; the digest is the message
            // as a Lightning node signs it, which verification below only matches if the
            // facade computes the same one
            assertEquals("LNURLcash:$domain:$amount:$pubkey", message, at)
            assertEquals(certificate.str("digest"), lightningSignedDigest(message).hex(), at)

            assertEquals(cs1, encodeCs1WithAmount(amount, signature), at)
            assertEquals(Cs1(amount, signature), decodeCs1WithAmount(cs1), at)
            assertTrue(isCs1WithAmount(cs1), at)
            assertEquals(signature, decodeAnyCs1(cs1), at)
            assertNull(decodeCs1(cs1), "$at: a current certificate is not legacy")
            assertFalse(isCs1(cs1), "$at: a current certificate is not legacy")

            // recovers to the mint's key, by the note's output key at its domain...
            assertTrue(verifyNoteSignatureHash(pubkey, domain, amount, cs1, mintPubkey), at)
            // ...and to nothing it does not cover
            assertFalse(verifyNoteSignatureHash(pubkey, domain, amount + 1, cs1, mintPubkey), "$at: another amount")
            val elsewhere = certificates.map { it.str("domain") }.first { it != domain }
            assertFalse(verifyNoteSignatureHash(pubkey, elsewhere, amount, cs1, mintPubkey), "$at: another mint")
            val otherParity = (if (mintPubkey.startsWith("02")) "03" else "02") + mintPubkey.substring(2)
            assertFalse(verifyNoteSignatureHash(pubkey, domain, amount, cs1, otherParity), "$at: the key's other parity")

            // from the note's ck1 alone, as its holder checks one
            if (domain == mintDomain) {
                val ck1 = ck1Of[pubkey] ?: error("$at: the certificate names a wallet note in the file")
                assertEquals(pubkey, noteIdOf(ck1), at)
                assertTrue(verifyNoteSignature(ck1, domain, amount, cs1, mintPubkey), at)
                assertFalse(verifyNoteSignature(ck1, domain, amount + 1, cs1, mintPubkey), "$at: another amount")
                assertFalse(verifyNoteSignature(ck1, elsewhere, amount, cs1, mintPubkey), "$at: another mint")
                val other = ck1Of.entries.first { it.key != pubkey }.value
                assertFalse(verifyNoteSignature(other, domain, amount, cs1, mintPubkey), "$at: another note")
            }
        }

        // a certificate signed before luds 92abbb3 names no domain: the full check still
        // reads it, the current-only hash check does not
        val legacy = part2.array("legacyCertificates").map { it.jsonObject }
        assertTrue(legacy.isNotEmpty())
        for (certificate in legacy) {
            val pubkey = certificate.str("notePubkey")
            val amount = certificate.long("amountMsat")
            assertEquals("LNURLcash:$amount:$pubkey", certificate.str("message"))
            val cs1 = certificate.str("cs1")
            assertFalse(verifyNoteSignatureHash(pubkey, mintDomain, amount, cs1, mintPubkey), "legacy $amount: not current")
            assertTrue(verifyNoteSignature(ck1Of.getValue(pubkey), mintDomain, amount, cs1, mintPubkey), "legacy $amount")
        }
    }

    @Test
    fun `address proofs are bound to the service, the username and the cx1`() {
        val shareKeyOf = part2.array("branches").flatMap { branch ->
            branch.jsonObject.array("accounts").flatMap { account ->
                account.jsonObject.array("shares").map { it.jsonObject.str("cx1") to it.jsonObject.str("sharePrivateKey") }
            }
        }.toMap()
        val proofs = part2.array("addressProofs").map { it.jsonObject }
        assertEquals(7, proofs.size)
        val actions = proofs.map { it.str("action") }.toSet()
        assertEquals(setOf("register", "update", "unregister"), actions)
        for (proof in proofs) {
            val action = proof.str("action")
            val domain = proof.str("domain")
            val username = proof.str("username")
            val cx1 = proof.strOrNull("cx1")
            val signerCx1 = proof.str("signerCx1")
            val signature = proof.str("signature")
            val at = "$action $username@$domain"

            // the message the digest is of, in the spec's own words
            val expected = "LNURLcash:$action:$domain:${username.lowercase()}" + (cx1?.let { ":$it" } ?: "")
            assertEquals(expected, proof.str("message"), at)
            assertEquals(proof.str("digest"), sha256(expected.toByteArray()).hex(), at)

            val key = shareKeyOf.getValue(signerCx1)
            assertEquals(signature, signAddressProof(key, action, domain, username, cx1), at)
            assertTrue(verifyAddressProof(signerCx1, signature, action, domain, username, cx1), at)
            assertEquals(proof.str("signerPubkey"), decodeCx1(signerCx1)!!.pubkeyXOnly, at)

            // and to nothing else
            assertFalse(verifyAddressProof(signerCx1, signature, action, "other.example", username, cx1), "$at: another service")
            assertFalse(verifyAddressProof(signerCx1, signature, action, domain, "mallory", cx1), "$at: another username")
            if (cx1 != null) {
                val other = shareKeyOf.keys.first { it != cx1 && it != signerCx1 }
                assertFalse(verifyAddressProof(signerCx1, signature, action, domain, username, other), "$at: another cx1")
            }
        }
    }

    @Test
    fun `the tweak schemes before LUD-26 are still readable`() {
        val legacy = part2.obj("legacy")
        assertTrue(legacy.bool("superseded"))
        assertEquals(0u, LEGACY_PURPOSE_WALLET)
        assertEquals(1u, LEGACY_PURPOSE_CHANGE)
        assertEquals(2u, LEGACY_PURPOSE_LIGHTNING_ADDRESS)

        val branches = legacy.array("branches").map { it.jsonObject }
        assertEquals(4, branches.size)
        var graded = 0
        for (branch in branches) {
            val host = branch.str("host")
            val root = deriveCashRoot(bip39Seed(branch.str("mnemonic")))
            // the host as stored, port included: that is the string the old scheme hashed
            val node = legacyBranchNode(root, host)
            assertEquals(branch.str("addressNode"), node, host)
            val cx1 = legacyCx1Of(node)
            assertEquals(branch.str("cx1"), cx1, host)
            // the 64-byte shape is not a LUD-26 cx1, and the other way round
            assertFalse(isCx1(cx1), host)
            assertNull(decodeCx1(cx1), host)

            fun grade(purpose: UInt?, note: JsonObject, at: String) {
                val index = note.long("index").toUInt()
                val pubkey = legacyDeriveNotePubkey(cx1, purpose, index)
                assertEquals(note.str("notePubkey"), pubkey, at)
                assertEquals(note.str("cp1"), encodeCp1(pubkey), at)
                val secretKey = legacyDeriveNoteSecretKey(node, purpose, index)
                assertEquals(note.str("noteSecretKey"), secretKey, at)
                graded++
            }
            for (note in branch.array("notes").map { it.jsonObject }) {
                grade(note.long("purpose").toUInt(), note, "$host purpose ${note.long("purpose")} #${note.long("index")}")
            }
            for (note in branch.array("prePurpose").map { it.jsonObject }) {
                grade(null, note, "$host pre-purpose #${note.long("index")}")
            }
        }
        assertEquals(4 * (5 + 3), graded)
    }

    @Test
    fun `valid and invalid strings`() {
        // the payload as hex, or null, for each of the four types
        fun decode(type: String, value: String): String? {
            val (decoded, claims) = when (type) {
                "cp1" -> decodeCp1(value) to isCp1(value)
                "ck1" -> decodeCk1(value) to isCk1(value)
                "cs1" -> decodeCs1WithAmount(value)?.signature to isCs1WithAmount(value)
                "cx1" -> decodeCx1(value)?.let { it.chainCode + it.pubkey } to isCx1(value)
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

    /** An extension, not LUD-25 or LUD-26: the same tree rooted in a Nostr identity key. */
    @Test
    fun `a tree rooted in a Nostr key`() {
        val vectors = Vectors.load("nostr-seed.json")
        assertTrue(vectors.bool("extension"))
        val label = vectors.str("label")
        assertEquals("LNURLcash/nostr-seed", label)

        val cases = vectors.array("cases").map { it.jsonObject }
        assertEquals(4, cases.size)
        var graded = 0
        for (case in cases) {
            val host = case.str("host")
            val domain = case.str("domain")
            val identity = case.str("identity")

            val seed = deriveNostrCashSeed(identity)
            assertEquals(case.str("seed"), seed, host)
            // the label is the whole of the construction
            assertEquals(seed, hmacSha256(identity.unhex(), label.toByteArray()).hex(), host)

            val serviceRoot = deriveNostrServiceRoot(identity, host)
            assertEquals(case.str("serviceRoot"), serviceRoot, host)
            // nothing new past the seed: the ordinary LUD-26 root from it
            assertEquals(serviceRoot, deriveServiceRoot(deriveCashRoot(seed), host), host)
            graded += gradeAccounts(serviceRoot, domain, case.array("accounts"), host)
        }
        assertTrue(graded > 0)
    }

    /**
     * Wallet, change and share notes of every account below one service root:
     * keys, `cp1`, `ck1`, and each share's `cx1` with the notes a mint derives
     * from it by public derivation. Returns how many notes it graded.
     */
    private fun gradeAccounts(
        serviceRoot: String,
        domain: String,
        accounts: kotlinx.serialization.json.JsonArray,
        at: String,
    ): Int {
        var graded = 0
        for (account in accounts.map { it.jsonObject }) {
            val a = account.long("account").toInt()
            val wallet = account.array("wallet").map { it.jsonObject }
            val change = account.array("change").map { it.jsonObject }
            assertTrue(wallet.isNotEmpty() && change.isNotEmpty(), "$at account $a")
            for (note in wallet) {
                val i = note.long("index").toInt()
                gradeNote(deriveNoteKey(serviceRoot, NotePath.Wallet(a, i)), note, domain, "$at S/$a'/0/$i")
                assertEquals(deriveNoteKey(serviceRoot, NotePath.Wallet(a, i)), deriveWalletNoteKey(serviceRoot, a, i))
                graded++
            }
            for (note in change) {
                val i = note.long("index").toInt()
                gradeNote(deriveNoteKey(serviceRoot, NotePath.Change(a, i)), note, domain, "$at S/$a'/1/$i")
                assertEquals(deriveNoteKey(serviceRoot, NotePath.Change(a, i)), deriveChangeNoteKey(serviceRoot, a, i))
                graded++
            }
            for (share in account.array("shares").map { it.jsonObject }) {
                val n = share.long("share").toInt()
                val node = deriveShareNode(serviceRoot, a, n)
                // a share's private key signs its registrations; it hands out only the cx1
                assertEquals(share.str("sharePrivateKey"), node.substring(0, 64), "$at share $a'/$n")
                assertEquals(share.str("chainCode"), node.substring(64), "$at share $a'/$n")
                val cx1 = share.str("cx1")
                assertEquals(cx1, cashNodeToCx1(node), "$at share $a'/$n")
                assertTrue(isCx1(cx1))
                val decoded = decodeCx1(cx1)
                assertEquals(Cx1(share.str("chainCode"), share.str("sharePubkey")), decoded, "$at share $a'/$n")
                assertNotNull(decoded)
                assertEquals(cx1, encodeCx1(decoded), "$at share $a'/$n")
                assertEquals(cx1, encodeCx1(decoded.chainCode, decoded.pubkey), "$at share $a'/$n")
                for (note in share.array("notes").map { it.jsonObject }) {
                    val i = note.long("index").toInt()
                    val where = "$at S/$a'/2/$n'/$i"
                    gradeNote(deriveNoteKey(serviceRoot, NotePath.Share(a, n, i)), note, domain, where)
                    assertEquals(
                        deriveNoteKey(serviceRoot, NotePath.Share(a, n, i)),
                        deriveShareNoteKey(serviceRoot, a, n, i),
                    )
                    // a mint holding only the cx1 finds the same note, by CKDpub, from both overloads
                    assertEquals(note.str("notePubkey"), deriveNotePubkey(cx1, i.toLong()), where)
                    assertEquals(note.str("notePubkey"), deriveNotePubkey(cx1, i.toUInt()), where)
                    graded++
                }
            }
        }
        return graded
    }

    private fun gradeNote(key: NoteKey, note: JsonObject, domain: String, at: String) {
        assertEquals(note.str("noteSecretKey"), key.secretKey, at)
        assertEquals(note.str("notePubkeyCompressed"), key.pubkey, at)
        assertEquals(note.str("notePubkey"), key.outputKey, at)
        val cp1 = note.str("cp1")
        assertEquals(cp1, encodeCp1(key.outputKey), at)
        assertEquals(key.outputKey, decodeCp1(cp1), at)
        assertTrue(isCp1(cp1), at)

        // all-zero aux_rand: one key at one domain reproduces one ck1, byte for byte
        val payload = signNoteOwnership(key.secretKey, domain) // Q (32) || Schnorr sig (64), 96 bytes
        assertEquals(note.str("keyPathSignature"), payload.substring(64), at)
        val ck1 = note.str("ck1")
        assertEquals(ck1, encodeCk1(payload), at)
        assertEquals(payload, decodeCk1(ck1), at)
        assertTrue(isCk1(ck1), at)

        // the service's side: the ck1 alone gives the note it is filed under, at its own mint only
        assertEquals(NoteOwner(key.outputKey, legacy = false), recoverNoteOwnershipPubkey(payload, domain), at)
        assertNull(recoverNoteOwnershipPubkey(payload, "other.example"), "$at: a spend is bound to its mint")
        assertEquals(key.outputKey, noteIdOf(ck1), at)
        assertEquals(cp1, noteLookupOf(ck1), at)
        assertFalse(key.toString().contains(key.secretKey), "$at: a NoteKey must not print its secret")
    }
}

// ---- the arithmetic the facade has no function for ----

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun sha256(vararg parts: ByteArray): ByteArray =
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
internal fun bip39Seed(mnemonic: String): String =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        .generateSecret(PBEKeySpec(mnemonic.toCharArray(), "mnemonic".toByteArray(), 2048, 512))
        .encoded
        .hex()
