package com.lnurlcash

import uniffi.lnurlcash_core.FfiCx1
import uniffi.lnurlcash_core.FfiCs1
import uniffi.lnurlcash_core.FfiMintFee
import uniffi.lnurlcash_core.applyMintFee as coreApplyMintFee
import uniffi.lnurlcash_core.buildNoteUrl as coreBuildNoteUrl
import uniffi.lnurlcash_core.decodeBolt11AmountMsat as coreDecodeBolt11AmountMsat
import uniffi.lnurlcash_core.describeMintFee as coreDescribeMintFee
import uniffi.lnurlcash_core.buildNoteInfoUrlByHash as coreBuildNoteInfoUrlByHash
import uniffi.lnurlcash_core.cashDomainIndices as coreCashDomainIndices
import uniffi.lnurlcash_core.cashNodeToCx1 as coreCashNodeToCx1
import uniffi.lnurlcash_core.decodeCk1 as coreDecodeCk1
import uniffi.lnurlcash_core.decodeCp1 as coreDecodeCp1
import uniffi.lnurlcash_core.decodeCs1 as coreDecodeCs1
import uniffi.lnurlcash_core.decodeAnyCs1 as coreDecodeAnyCs1
import uniffi.lnurlcash_core.decodeCs1WithAmount as coreDecodeCs1WithAmount
import uniffi.lnurlcash_core.decodeCx1 as coreDecodeCx1
import uniffi.lnurlcash_core.deriveCashAddressNode as coreDeriveCashAddressNode
import uniffi.lnurlcash_core.deriveCashChild as coreDeriveCashChild
import uniffi.lnurlcash_core.deriveCashDomainNode as coreDeriveCashDomainNode
import uniffi.lnurlcash_core.deriveCashMaster as coreDeriveCashMaster
import uniffi.lnurlcash_core.deriveCashRoot as coreDeriveCashRoot
import uniffi.lnurlcash_core.deriveNostrAddressNode as coreDeriveNostrAddressNode
import uniffi.lnurlcash_core.deriveNostrCashSeed as coreDeriveNostrCashSeed
import uniffi.lnurlcash_core.deriveNotePubkey as coreDeriveNotePubkey
import uniffi.lnurlcash_core.deriveNoteRoot as coreDeriveNoteRoot
import uniffi.lnurlcash_core.deriveNoteSecret as coreDeriveNoteSecret
import uniffi.lnurlcash_core.deriveNoteSecretKey as coreDeriveNoteSecretKey
import uniffi.lnurlcash_core.encodeCk1 as coreEncodeCk1
import uniffi.lnurlcash_core.encodeCp1 as coreEncodeCp1
import uniffi.lnurlcash_core.encodeCs1 as coreEncodeCs1
import uniffi.lnurlcash_core.encodeCs1WithAmount as coreEncodeCs1WithAmount
import uniffi.lnurlcash_core.encodeCx1 as coreEncodeCx1
import uniffi.lnurlcash_core.generateNoteSecret as coreGenerateNoteSecret
import uniffi.lnurlcash_core.grossUpForMintFee as coreGrossUpForMintFee
import uniffi.lnurlcash_core.hashK1 as coreHashK1
import uniffi.lnurlcash_core.isAllowedServiceUrl as coreIsAllowedServiceUrl
import uniffi.lnurlcash_core.isAnyCs1 as coreIsAnyCs1
import uniffi.lnurlcash_core.isBolt11Invoice as coreIsBolt11Invoice
import uniffi.lnurlcash_core.isCk1 as coreIsCk1
import uniffi.lnurlcash_core.isCp1 as coreIsCp1
import uniffi.lnurlcash_core.isCs1 as coreIsCs1
import uniffi.lnurlcash_core.isCx1 as coreIsCx1
import uniffi.lnurlcash_core.isPreimage as coreIsPreimage
import uniffi.lnurlcash_core.mintAddressUrl as coreMintAddressUrl
import uniffi.lnurlcash_core.noteDeclaredAmount as coreNoteDeclaredAmount
import uniffi.lnurlcash_core.noteIdOf as coreNoteIdOf
import uniffi.lnurlcash_core.noteK1 as coreNoteK1
import uniffi.lnurlcash_core.noteLookupOf as coreNoteLookupOf
import uniffi.lnurlcash_core.noteSignature as coreNoteSignature
import uniffi.lnurlcash_core.parseMintFee as coreParseMintFee
import uniffi.lnurlcash_core.recoverNoteOwnershipPubkey as coreRecoverNoteOwnershipPubkey
import uniffi.lnurlcash_core.resolveLnurlInput as coreResolveLnurlInput
import uniffi.lnurlcash_core.resolveMintInput as coreResolveMintInput
import uniffi.lnurlcash_core.resolveNoteInput as coreResolveNoteInput
import uniffi.lnurlcash_core.sameInvoice as coreSameInvoice
import uniffi.lnurlcash_core.signNoteOwnership as coreSignNoteOwnership
import uniffi.lnurlcash_core.signAddressProof as coreSignAddressProof
import uniffi.lnurlcash_core.verifyNoteSignature as coreVerifyNoteSignature
import uniffi.lnurlcash_core.verifyNoteSignatureHash as coreVerifyNoteSignatureHash
import uniffi.lnurlcash_core.withNewK1 as coreWithNewK1
import uniffi.lnurlcash_core.withoutK1 as coreWithoutK1

/**
 * The pure half of LNURLcash: everything that needs no network.
 *
 * These delegate to the Rust core, which is the one audited implementation of
 * the parts that lose money when they are wrong. The signatures here differ in
 * one respect only: amounts are `Long` rather than the bindings' `ULong`, since
 * `ULong` is awkward from Kotlin and unusable from Java, and no realistic msat
 * amount comes close to overflowing a signed 64-bit integer - 21 million BTC is
 * 2.1e15 msat, about a thousandth of `Long.MAX_VALUE`.
 */

/** A fresh 32-byte note secret from the OS CSPRNG. Wallets generate these, never services. */
public fun generateNoteSecret(): String = coreGenerateNoteSecret()

/** A note's id: `sha256(secret)`, the `h` disclosed on a mutation. */
public fun hashK1(k1: String): String = coreHashK1(k1)

/**
 * `m/139'`, a wallet's own root for everything Part 2 (Wallet-side ownership
 * proofs) derives beneath it - not Part 1, which is plain randomness with no
 * derivation at all.
 *
 * `seedHex` is raw seed bytes as hex; a 64-byte BIP39 seed is the interop
 * case. The returned node is `privateKey || chainCode`, 64 bytes of hex, and
 * is bearer material for every note beneath it.
 */
public fun deriveCashRoot(seedHex: String): String = coreDeriveCashRoot(seedHex)

/**
 * `m/139'/d1/d2/d3/d4` for one mint - [deriveCashAddressNode]'s literal path,
 * with `d1..d4` the four RAW uint32 out of `HMAC-SHA256(m/139'/0, host)`.
 * BIP-32 reads any index at or above 2^31 as hardened, so which of those
 * levels are hardened is decided by the mint's own host name. Masking the top
 * bit, or hardening all four, derives a different tree from every conforming
 * wallet and restores nothing, silently.
 *
 * Every unhardened level sits at or above this node, so a hardware signer
 * provisioned with it rather than the seed needs no elliptic curve at all.
 * The cost is that whoever derives it can derive every note key held at that
 * mint: provisioning material, one mint's subtree, not the wallet.
 */
public fun deriveCashDomainNode(rootHex: String, host: String): String =
    coreDeriveCashDomainNode(rootHex, host)

/** The four raw uint32 levels a mint's subtree hangs off. */
public fun cashDomainIndices(rootHex: String, host: String): List<UInt> =
    coreCashDomainIndices(rootHex, host)

/**
 * The BIP-32 master node of a seed, as `privateKey || chainCode` hex.
 *
 * For walking a path this library does not name. [deriveCashRoot] is `m/139'`
 * beneath it, and it is here so a caller checking a path by hand starts from
 * the same node every other implementation does.
 */
public fun deriveCashMaster(seedHex: String): String = coreDeriveCashMaster(seedHex)

/**
 * One BIP-32 CKDpriv step: hardened when [index] is at or above 2^31, and only
 * then.
 *
 * The index decides, not the caller, which is exactly what LUD-25's raw uint32
 * levels need.
 */
public fun deriveCashChild(nodeHex: String, index: UInt): String = coreDeriveCashChild(nodeHex, index)

/**
 * The LEGACY pre-spec HMAC scheme's root, for finding notes minted before
 * LUD-25 specified a derivation. Do not mint under it.
 */
public fun deriveNoteRoot(seedHex: String): String = coreDeriveNoteRoot(seedHex)

/** The LEGACY scheme's i-th secret at [host]. Do not mint under it. */
public fun deriveNoteSecret(rootHex: String, host: String, index: UInt): String =
    coreDeriveNoteSecret(rootHex, host, index)

/**
 * The informational GET for a note named by its hash rather than its secret,
 * so nothing spendable goes on the wire.
 *
 * What a restore walk uses: a walk queries a whole gap window of indices the
 * wallet has not minted into yet, and asking by secret would publish exactly
 * the secrets it is about to mint under. A rejection proves nothing - a
 * service that does not index by hash, one that never issued the note, and one
 * that BURNED it all answer identically, which is why the persisted per-host
 * counter is the real backup and the scan is only a fallback.
 *
 * [h] may also be a Part 2 `cp1`, which goes as `p`, the name LUD-25 now uses.
 * A hash keeps `h`, which every mint that ever took a hash lookup understands.
 * [noteLookupOf] gives the right one for either kind of k1. A `ck1` is refused:
 * it spends the note, so it is never a private lookup.
 */
public fun buildNoteInfoUrlByHash(withdrawLink: String, h: String): String? =
    coreBuildNoteInfoUrlByHash(withdrawLink, h)

/** Whether a string is 32 bytes of hex - the shape of a note secret. */
public fun isPreimage(value: String): Boolean = coreIsPreimage(value)

/**
 * Verify a note's signature against the mint's pubkey, offline.
 *
 * Accepts the recovery id at either end of the signature, because
 * implementations disagree about which end it belongs on. Trying both is safe:
 * the wrong ordering recovers an unrelated key that cannot match.
 *
 * [k1] may be a Part 2 `ck1`: its id is the key it recovers to, found locally,
 * so checking one needs no network either. [signatureHex] may be a current
 * amount-bearing `cs1`, a legacy fixed-prefix `cs1`, or the same 65 bytes as
 * hex. Decode the certificate separately when its carried amount is needed.
 * A k1 that is neither 32 bytes of hex nor a `ck1` that recovers has no id to
 * check, and is a plain `false`.
 */
public fun verifyNoteSignature(
    k1: String,
    amountMsat: Long,
    signatureHex: String,
    mintPubkeyHex: String,
): Boolean = coreVerifyNoteSignature(k1, amountMsat.toULong(), signatureHex, mintPubkeyHex)

/**
 * [verifyNoteSignature] by the note's id rather than its k1: a hash, or a Part
 * 2 note's public key as hex (what [decodeCp1] gives for a `cp1`).
 *
 * For checking a certificate without the secret that spends the note. A
 * watcher holding only a branch's `cx1` never has that secret, and a wallet
 * that named an output with [LnurlcashClient.rotateWithHash] only needs the
 * output it named.
 */
public fun verifyNoteSignatureHash(
    h: String,
    amountMsat: Long,
    signatureHex: String,
    mintPubkeyHex: String,
): Boolean = coreVerifyNoteSignatureHash(h, amountMsat.toULong(), signatureHex, mintPubkeyHex)

// ---- LUD-25 Part 2: notes keyed by a public key ----
//
// A Part 2 note swaps the hash for a key pair. The holder keeps `sk` and the
// service only ever sees `pk`, written `cp1...`. To spend the note the holder
// hands over `ck1...`, a recoverable signature by `sk` over a fixed message;
// the service recovers `pk` from it and finds the note. The mint certifies each
// note with `cs1...`, the signature it has always made, over `hex(pk)` instead
// of a hash, so a recipient can check a note offline with nothing but its `ck1`
// and `cs1`.
//
// Raw bytes cross as hex, as everywhere else here, and the four bech32m
// strings as themselves. A note secret key, a `ck1`, an address node and a
// Nostr cash seed are all bearer material: store them the way notes are
// stored, and never log them. A `cx1` spends nothing, but links every note on
// its branch.
//
// Every decoder returns null rather than throwing for anything that is not
// exactly its own type at exactly its own length. BIP-350's rules apply: all
// uppercase is the same string, and mixed case, a bech32 checksum where a
// bech32m one belongs, the wrong prefix and non-zero padding are all refused.
// There is no 90-character limit; `ck1`, `cs1` and `cx1` all run past it.

/** A note's 32-byte x-only public key as a `cp1`: what a wallet discloses as an output. */
public fun encodeCp1(pubkeyXOnlyHex: String): String = coreEncodeCp1(pubkeyXOnlyHex)

/** The 32-byte key inside a `cp1`, as hex, or null for anything that is not one. */
public fun decodeCp1(value: String): String? = coreDecodeCp1(value)

public fun isCp1(value: String): Boolean = coreIsCp1(value)

/**
 * A 65-byte ownership signature, `r || s || recovery id`, as a `ck1`.
 *
 * That string spends the note, so it is as secret as the key that made it.
 */
public fun encodeCk1(signatureHex: String): String = coreEncodeCk1(signatureHex)

/** The 65 bytes inside a `ck1`, as hex, or null for anything that is not one. */
public fun decodeCk1(value: String): String? = coreDecodeCk1(value)

public fun isCk1(value: String): Boolean = coreIsCk1(value)

/** A legacy fixed-prefix `cs1`, retained so existing callers keep working. */
public fun encodeCs1(signatureHex: String): String = coreEncodeCs1(signatureHex)

/** Decode only a legacy fixed-prefix `cs1`. New code should use [decodeCs1WithAmount]. */
public fun decodeCs1(value: String): String? = coreDecodeCs1(value)

/** True only for a legacy fixed-prefix `cs1`. */
public fun isCs1(value: String): Boolean = coreIsCs1(value)

/**
 * Encode a current mint certificate with its amount in the human-readable
 * prefix, using the same amount suffix rules as BOLT 11.
 *
 * The certificate proves issuance and spends nothing, so it can travel with a
 * note in the open. The signature itself must cover the same amount; encoding
 * does not sign or verify it.
 */
public fun encodeCs1WithAmount(amountMsat: Long, signatureHex: String): String {
    require(amountMsat >= 0) { "amountMsat must be non-negative" }
    return coreEncodeCs1WithAmount(amountMsat.toULong(), signatureHex)
}

/**
 * Decode a current amount-bearing `cs1`, including the amount carried by its
 * prefix. Returns null when the wire amount cannot be represented by this
 * facade's signed [Long] amount type.
 */
public fun decodeCs1WithAmount(value: String): Cs1? =
    coreDecodeCs1WithAmount(value)
        ?.takeIf { it.amountMsat <= Long.MAX_VALUE.toULong() }
        ?.toKotlin()

/** True only for a current amount-bearing `cs1`. */
public fun isCs1WithAmount(value: String): Boolean = decodeCs1WithAmount(value) != null

/** Decode the signature from either a current or legacy `cs1`. */
public fun decodeAnyCs1(value: String): String? = coreDecodeAnyCs1(value)

/** True for either a current or legacy `cs1`. */
public fun isAnyCs1(value: String): Boolean = coreIsAnyCs1(value)

/**
 * A watch-only branch as a `cx1`: its x-only public key followed by its chain
 * code.
 *
 * Whoever holds one can derive every note key on the branch, and so link every
 * note on it to the others, but can spend none of them. That is what lets a
 * mint holding a registered `cx1` pay straight to the holder's next key, and it
 * is also why handing one out is a privacy decision.
 */
public fun encodeCx1(pubkeyXOnlyHex: String, chainCodeHex: String): String =
    coreEncodeCx1(pubkeyXOnlyHex, chainCodeHex)

/** Both halves of a `cx1`, or null for anything that is not one. */
public fun decodeCx1(value: String): Cx1? = coreDecodeCx1(value)?.toKotlin()

public fun isCx1(value: String): Boolean = coreIsCx1(value)

/**
 * A note's public key at [index], from the watch-only half of a branch alone.
 *
 * [index] is any `UInt` and is never hardened. The tweak is BIP-341's, so a
 * watcher holding only the `cx1` computes the same key the holder does.
 *
 * An index whose tweak lands at or above the curve order is an error rather
 * than reduced. A reduced key is one no other implementation derives, and a
 * note minted to it is a note nobody can find, so use the next index. The
 * odds are around 2^-128, which is why this is worth saying and not worth
 * designing around.
 */
public fun deriveNotePubkey(branchPubkeyXOnlyHex: String, chainCodeHex: String, index: UInt): String =
    coreDeriveNotePubkey(branchPubkeyXOnlyHex, chainCodeHex, index)

/**
 * Java-callable overload of [deriveNotePubkey], with [index] in `0..4294967295`.
 *
 * @throws IllegalArgumentException if [index] is outside the unsigned 32-bit range.
 */
public fun deriveNotePubkey(branchPubkeyXOnlyHex: String, chainCodeHex: String, index: Long): String =
    deriveNotePubkey(branchPubkeyXOnlyHex, chainCodeHex, checkedNoteIndex(index))

/**
 * The secret key behind [deriveNotePubkey]. Bearer material.
 *
 * A branch key whose point has odd y is negated first. A `cx1` carries only x,
 * which names the even-y point, so without the negation the holder's keys and
 * a watcher's would disagree on half of all branches.
 */
public fun deriveNoteSecretKey(branchPrivateKeyHex: String, chainCodeHex: String, index: UInt): String =
    coreDeriveNoteSecretKey(branchPrivateKeyHex, chainCodeHex, index)

/**
 * Java-callable overload of [deriveNoteSecretKey], with [index] in `0..4294967295`.
 * Returns bearer material, just like the unsigned overload.
 *
 * @throws IllegalArgumentException if [index] is outside the unsigned 32-bit range.
 */
public fun deriveNoteSecretKey(branchPrivateKeyHex: String, chainCodeHex: String, index: Long): String =
    deriveNoteSecretKey(branchPrivateKeyHex, chainCodeHex, checkedNoteIndex(index))

private fun checkedNoteIndex(index: Long): UInt {
    require(index in 0L..0xFFFF_FFFFL) { "index must be in 0..4294967295" }
    return index.toUInt()
}

/**
 * The 96-byte `pk || sig` ownership payload, as hex: a BIP-340 Schnorr
 * signature over `sha256("LNURLcash")`, a fixed 32-byte digest, with the
 * note's x-only pubkey attached. [encodeCk1] it for the wire. Either way,
 * it spends the note.
 *
 * All-zero auxiliary randomness, so one key always reproduces one `ck1`
 * byte for byte: a wallet restored from its seed can re-sign every note it
 * ever held. Deterministic is not unique, though. See [noteIdOf].
 */
public fun signNoteOwnership(secretKeyHex: String): String = coreSignNoteOwnership(secretKeyHex)

/**
 * A register/update or unregister proof by the address branch's index-0 key:
 * a 64-byte BIP-340 Schnorr signature over `sha256("LNURLcash:<action>:<username>")`,
 * as hex.
 *
 * [username] must be the same normalised value sent to the service. Only the
 * reference actions `register` and `unregister` are accepted.
 */
public fun signAddressProof(indexZeroSecretKeyHex: String, action: String, username: String): String =
    coreSignAddressProof(indexZeroSecretKeyHex, action, username)

/**
 * The note's x-only public key, recovered offline from its 96-byte ownership
 * payload, or null for anything that does not verify.
 *
 * Verifies against the current `sha256("LNURLcash")` digest first, then
 * falls back to the pre-2026-09-16 raw-message scheme so a note minted under
 * it stays redeemable until it is rotated - this function never *produces*
 * that shape, only reads it back (see [signNoteOwnership]).
 *
 * This is what a service does with a `ck1` to find the note: [decodeCk1] then
 * this gives the key the note is filed under.
 */
public fun recoverNoteOwnershipPubkey(signatureHex: String): String? =
    coreRecoverNoteOwnershipPubkey(signatureHex)

/**
 * The id a service files a note under: `sha256(k1)` for a Part 1 secret, the
 * key a `ck1` recovers to for a Part 2 note, and null for anything else.
 *
 * Compare notes by this, never by k1. One Part 2 note has more than one valid
 * `ck1` string (anyone can flip a signature to its high-S twin, and it still
 * recovers to the same key), so a wallet deduplicating by string would hold
 * one note twice.
 */
public fun noteIdOf(k1: String): String? = coreNoteIdOf(k1)

/**
 * What to look a note up by without disclosing it: the hash for a Part 1
 * secret, the `cp1` for a Part 2 note. Pass it to [buildNoteInfoUrlByHash].
 */
public fun noteLookupOf(k1: String): String? = coreNoteLookupOf(k1)

/**
 * `m/139'/d1/d2/d3/d4` for one mint, as `privateKey || chainCode` hex: the
 * address branch Part 2 note keys hang off - the exact node
 * [deriveCashDomainNode] derives, with no separate purpose. An earlier
 * reference-wallet extension deterministically derived Part 1 secrets off
 * that same node too, under its own `1'` sub-purpose kept just for this
 * branch to avoid colliding with it; that extension is gone, so there is
 * nothing left to collide with.
 *
 * Bearer material for every note on the branch. Hand out [cashNodeToCx1] of it,
 * never the node.
 */
public fun deriveCashAddressNode(rootHex: String, host: String): String =
    coreDeriveCashAddressNode(rootHex, host)

/** The watch-only half of a branch node: what a mint or a watcher is given. */
public fun cashNodeToCx1(nodeHex: String): Cx1 = coreCashNodeToCx1(nodeHex).toKotlin()

/**
 * Not LUD-25: the cash seed of a Nostr identity key,
 * `HMAC-SHA256(key = the secret key, msg = "LNURLcash/nostr-seed")`. Bearer
 * material for every note on the identity's branches.
 */
public fun deriveNostrCashSeed(secretKeyHex: String): String = coreDeriveNostrCashSeed(secretKeyHex)

/**
 * Not LUD-25: one mint's address branch for a Nostr identity key, as a 64-byte
 * hex node. The address path from [deriveNostrCashSeed]'s seed, unchanged.
 *
 * A lightning address on a Nostr-native mint belongs to an npub, and a holder
 * with no BIP-39 words (a hardware signer that keeps only its identity key, or
 * a wallet that never made any) can still be paid to keys of its own. Whoever
 * can restore the identity key can rebuild every note paid to the branch, with
 * or without the device that received them. A mint sees an ordinary `cx1`
 * either way.
 *
 * Bearer material: hand out [cashNodeToCx1] of it.
 */
public fun deriveNostrAddressNode(secretKeyHex: String, host: String): String =
    coreDeriveNostrAddressNode(secretKeyHex, host)

/**
 * Resolve scanned or pasted text to a note URL - bech32, `lnurlw://`, or https.
 *
 * The URL's k1 must be 32 bytes of hex or a Part 2 `ck1` that recovers to a
 * key. A `cp1` there is refused: it names the note but cannot spend it.
 */
public fun resolveNoteInput(value: String): String? = coreResolveNoteInput(value)

/** Resolve a mint address, bare domain or bech32 LNURL to its payRequest URL. */
public fun resolveMintInput(value: String): String? = coreResolveMintInput(value)

public fun resolveLnurlInput(value: String): String? = coreResolveLnurlInput(value)

/**
 * Whether this library would fetch a URL: https anywhere, http only for
 * loopback and `.onion`. Applies to URLs a service supplies, not just ones a
 * user pastes.
 */
public fun isAllowedServiceUrl(value: String): Boolean = coreIsAllowedServiceUrl(value)

public fun mintAddressUrl(payUrl: String): String? = coreMintAddressUrl(payUrl)

public fun noteK1(url: String): String? = coreNoteK1(url)

/** What a note *claims* to be worth. Only a claim - see [NoteInfo.maxWithdrawableMsat]. */
public fun noteDeclaredAmountMsat(url: String): Long? = coreNoteDeclaredAmount(url)?.toLong()

public fun noteSignature(url: String): String? = coreNoteSignature(url)

public fun buildNoteUrl(withdrawLink: String, k1: String, amountMsat: Long? = null): String? =
    coreBuildNoteUrl(withdrawLink, k1, amountMsat?.toULong())

public fun withNewK1(url: String, k1: String, amountMsat: Long, signature: String? = null): String? =
    coreWithNewK1(url, k1, amountMsat.toULong(), signature)

public fun withoutK1(url: String, amountMsat: Long, signature: String? = null): String? =
    coreWithoutK1(url, amountMsat.toULong(), signature)

/** Parse a mint's advertised fee out of payRequest metadata. Null means fee-free. */
public fun parseMintFee(metadata: String): MintFee? = coreParseMintFee(metadata)?.toKotlin()

/** What a note will be worth after the mint withholds its fee. */
public fun applyMintFee(grossMsat: Long, fee: MintFee): Long =
    coreApplyMintFee(grossMsat.toULong(), fee.toFfi()).toLong()

/** The smallest invoice whose note nets [netMsat] after the fee. */
public fun grossUpForMintFee(netMsat: Long, fee: MintFee): Long =
    coreGrossUpForMintFee(netMsat.toULong(), fee.toFfi()).toLong()

public fun describeMintFee(fee: MintFee): String = coreDescribeMintFee(fee.toFfi())

public fun decodeBolt11AmountMsat(invoice: String): Long? =
    coreDecodeBolt11AmountMsat(invoice)?.toLong()

public fun isBolt11Invoice(value: String): Boolean = coreIsBolt11Invoice(value)

/** bolt11 is bech32, so case-insensitive: bind a proof to the invoice it reports on. */
public fun sameInvoice(a: String, b: String): Boolean = coreSameInvoice(a, b)

internal fun FfiMintFee.toKotlin(): MintFee =
    MintFee(baseFeeMsat = baseFeeMsat.toLong(), feePpm = feePpm.toLong())

internal fun MintFee.toFfi(): FfiMintFee =
    FfiMintFee(baseFeeMsat = baseFeeMsat.toULong(), feePpm = feePpm.toULong())

internal fun FfiCx1.toKotlin(): Cx1 = Cx1(pubkeyXOnly = pubkeyXOnly, chainCode = chainCode)

internal fun FfiCs1.toKotlin(): Cs1 = Cs1(amountMsat = amountMsat.toLong(), signature = signature)
