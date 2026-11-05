package com.lnurlcash

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import uniffi.lnurlcash_core.keyPathSighash

/**
 * LUD-26's own test vectors (luds `26-vectors.json`, carried as the `lud26` key
 * of spec-vectors.json), graded through the Kotlin facade: both seeds, both
 * accounts, the wallet, change and share chains, the `cx1`s, the key-path
 * spend's `ck1` and the three registration proofs.
 *
 * This is the spec's own file, not the conformance repo's wider part2.json, so
 * a disagreement between the two shows up here as a disagreement with the spec.
 */
class Lud26SpecVectorTest {

    @BeforeTest
    fun requireVectors() {
        assertTrue(
            Vectors.available,
            "conformance vectors not found at ${Vectors.directory} - " +
                "check out lnurlcash-conformance alongside this repo, or set LNURLCASH_CONFORMANCE",
        )
    }

    private val lud26: JsonObject by lazy { Vectors.load("spec-vectors.json").obj("lud26") }

    /** Service roots by derivation index, kept for the spend and registration vectors. */
    private val serviceRoots: List<String> by lazy {
        lud26.array("derivation").map { derivation ->
            val d = derivation.jsonObject
            deriveServiceRoot(deriveCashRoot(d.str("seed")), d.str("domain"))
        }
    }

    @Test
    fun `wallet, change and share derivation for both seeds and accounts`() {
        val derivations = lud26.array("derivation").map { it.jsonObject }
        assertEquals(2, derivations.size, "two seeds")
        var notes = 0
        var shares = 0
        for ((n, derivation) in derivations.withIndex()) {
            val domain = derivation.str("domain")
            val root = deriveCashRoot(derivation.str("seed"))
            assertEquals(derivation.str("cashHashingKey"), deriveCashChild(root, 0u).substring(0, 64), domain)
            assertEquals(
                derivation.array("d").map { it.jsonPrimitive.long.toUInt() },
                cashDomainIndices(root, domain),
                domain,
            )
            val serviceRoot = serviceRoots[n]

            val accounts = derivation.array("accounts").map { it.jsonObject }
            assertTrue(accounts.isNotEmpty())
            for (account in accounts) {
                val a = account.long("account").toInt()
                val chains = mapOf(
                    "wallet" to { i: Int -> NotePath.Wallet(a, i) },
                    "change" to { i: Int -> NotePath.Change(a, i) },
                )
                for ((chain, path) in chains) {
                    for (note in account.obj(chain).array("notes").map { it.jsonObject }) {
                        val i = note.long("i").toInt()
                        gradeNote(deriveNoteKey(serviceRoot, path(i)), note, "$domain $chain $a'/$i")
                        notes++
                    }
                }
                for (share in account.array("shareable").map { it.jsonObject }) {
                    val s = share.long("share").toInt()
                    val node = deriveShareNode(serviceRoot, a, s)
                    assertEquals(share.str("p"), node.substring(0, 64), "$domain share $a'/$s")
                    assertEquals(share.str("chaincode"), node.substring(64), "$domain share $a'/$s")
                    // the share is exported as 65 bytes: chain code, then the compressed key
                    val cx1 = share.str("cx1")
                    assertEquals(cx1, cashNodeToCx1(node), "$domain share $a'/$s")
                    assertEquals(Cx1(share.str("chaincode"), share.str("P")), decodeCx1(cx1), "$domain share $a'/$s")
                    assertEquals(cx1, encodeCx1(share.str("chaincode"), share.str("P")), "$domain share $a'/$s")
                    shares++
                    for (note in share.array("notes").map { it.jsonObject }) {
                        val i = note.long("i").toInt()
                        val at = "$domain share $a'/$s/$i"
                        gradeNote(deriveNoteKey(serviceRoot, NotePath.Share(a, s, i)), note, at)
                        // what a mint credits next, from the cx1 alone
                        assertEquals(note.str("Q"), deriveNotePubkey(cx1, i.toLong()), at)
                        notes++
                    }
                }
            }
        }
        // both seeds, every chain: the file would have to shrink for this to fall
        assertTrue(notes >= 12 && shares >= 4, "graded only $notes notes on $shares shares")
    }

    @Test
    fun `the key-path spend is a ck1 bound to its mint`() {
        val spend = lud26.obj("key_path_spend")
        val domain = spend.str("domain")
        val sk = spend.str("sk")
        val q = spend.str("Q")
        // the note is derivation[0]'s wallet note 0, whose key has odd y
        val key = deriveWalletNoteKey(serviceRoots[0], 0, 0)
        assertEquals(sk, key.secretKey)
        assertEquals(q, key.outputKey)
        assertEquals(spend.str("pk"), key.pubkey)
        assertTrue(key.pubkey.startsWith("03"), "the vector exists to exercise BIP-340's negation")

        assertEquals(spend.str("sighash"), keyPathSighash(q, domain))
        val payload = signNoteOwnership(sk, domain)
        assertEquals(q + spend.str("sig"), payload)
        assertEquals(spend.str("ck1"), encodeCk1(payload))
        assertEquals(NoteOwner(q, legacy = false), recoverNoteOwnershipPubkey(payload, domain))
        assertEquals(null, recoverNoteOwnershipPubkey(payload, "other.example"))
    }

    @Test
    fun `registration proofs for register, update and unregister`() {
        val registration = lud26.obj("registration")
        val domain = registration.str("domain")
        val username = registration.str("username")
        // derivation[1]'s account 0, shares 0 and 1
        val serviceRoot = serviceRoots[1]
        assertEquals(domain, lud26.array("derivation")[1].jsonObject.str("domain"))
        val old = deriveShareNode(serviceRoot, 0, 0).substring(0, 64)
        val new = deriveShareNode(serviceRoot, 0, 1).substring(0, 64)
        val cx1 = registration.str("cx1")
        val newCx1 = registration.str("new_cx1")
        assertEquals(cx1, cashNodeToCx1(deriveShareNode(serviceRoot, 0, 0)))
        assertEquals(newCx1, cashNodeToCx1(deriveShareNode(serviceRoot, 0, 1)))

        class Case(val proof: JsonObject, val action: String, val bound: String?, val signer: String, val signerCx1: String)
        val update = registration.obj("update")
        val cases = listOf(
            Case(registration.obj("register"), "register", cx1, old, cx1),
            // an update is signed by the key on file, naming the new cx1...
            Case(update.obj("sig"), "update", newCx1, old, cx1),
            // ...and a PUT also carries a register proof by the new share
            Case(update.obj("newsig"), "register", newCx1, new, newCx1),
            Case(registration.obj("unregister"), "unregister", null, new, newCx1),
        )
        for (case in cases) {
            val at = case.action
            val expected = "LNURLcash:${case.action}:$domain:$username" + (case.bound?.let { ":$it" } ?: "")
            assertEquals(case.proof.str("message"), expected, at)
            assertEquals(case.proof.str("digest"), sha256(expected.toByteArray()).hex(), at)
            val signature = signAddressProof(case.signer, case.action, domain, username, case.bound)
            assertEquals(case.proof.str("sig"), signature, at)
            assertTrue(verifyAddressProof(case.signerCx1, signature, case.action, domain, username, case.bound), at)
            // a proof by the other share is not this one's
            val other = if (case.signerCx1 == cx1) newCx1 else cx1
            assertFalse(verifyAddressProof(other, signature, case.action, domain, username, case.bound), "$at: another signer")
        }
    }

    private fun gradeNote(key: NoteKey, note: JsonObject, at: String) {
        assertEquals(note.str("sk"), key.secretKey, at)
        assertEquals(note.str("pk"), key.pubkey, at)
        assertEquals(note.str("Q"), key.outputKey, at)
        assertEquals(note.str("cp1"), encodeCp1(key.outputKey), at)
    }
}
