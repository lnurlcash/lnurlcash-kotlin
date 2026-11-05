package com.lnurlcash

import uniffi.lnurlcash_core.FfiCx1
import uniffi.lnurlcash_core.FfiNoteKey
import uniffi.lnurlcash_core.FfiNoteOwner
import uniffi.lnurlcash_core.FfiNotePath
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
import uniffi.lnurlcash_core.deriveCashChild as coreDeriveCashChild
import uniffi.lnurlcash_core.deriveCashDomainNode as coreDeriveCashDomainNode
import uniffi.lnurlcash_core.deriveCashMaster as coreDeriveCashMaster
import uniffi.lnurlcash_core.deriveCashRoot as coreDeriveCashRoot
import uniffi.lnurlcash_core.deriveNostrServiceRoot as coreDeriveNostrServiceRoot
import uniffi.lnurlcash_core.deriveNostrCashSeed as coreDeriveNostrCashSeed
import uniffi.lnurlcash_core.deriveNotePubkey as coreDeriveNotePubkey
import uniffi.lnurlcash_core.deriveNoteRoot as coreDeriveNoteRoot
import uniffi.lnurlcash_core.deriveNoteSecret as coreDeriveNoteSecret
import uniffi.lnurlcash_core.deriveNoteKey as coreDeriveNoteKey
import uniffi.lnurlcash_core.deriveServiceRoot as coreDeriveServiceRoot
import uniffi.lnurlcash_core.deriveShareNode as coreDeriveShareNode
import uniffi.lnurlcash_core.legacyBranchNode as coreLegacyBranchNode
import uniffi.lnurlcash_core.legacyCx1Of as coreLegacyCx1Of
import uniffi.lnurlcash_core.legacyDeriveNotePubkey as coreLegacyDeriveNotePubkey
import uniffi.lnurlcash_core.legacyDeriveNoteSecretKey as coreLegacyDeriveNoteSecretKey
import uniffi.lnurlcash_core.verifyAddressProof as coreVerifyAddressProof
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
 * `m/139'`, a wallet's own root for everything LUD-26 (wallet-side derivation)
 * derives beneath it - not LUD-25's Part 1, which is plain randomness with no
 * derivation at all.
 *
 * `seedHex` is raw seed bytes as hex; a 64-byte BIP39 seed is the interop
 * case. The returned node is `privateKey || chainCode`, 64 bytes of hex, and
 * is bearer material for every note beneath it. Take [deriveServiceRoot] of it
 * per mint rather than passing it around.
 */
public fun deriveCashRoot(seedHex: String): String = coreDeriveCashRoot(seedHex)

/**
 * `m/139'/d1/d2/d3/d4` over [host] exactly as given, port included, with
 * `d1..d4` the four RAW uint32 out of `HMAC-SHA256(m/139'/0, host)`. BIP-32
 * reads any index at or above 2^31 as hardened, so which of those levels are
 * hardened is decided by the mint's own host name. Masking the top bit, or
 * hardening all four, derives a different tree from every conforming wallet
 * and restores nothing, silently.
 *
 * LUD-26's per-mint root is [deriveServiceRoot], which drops the port first;
 * for a host without one the two are the same node. Use that to mint. This one
 * is for diagnosing a restore, and for a host stored with its port.
 *
 * Bearer material for every note held at that mint.
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
 * Verify a note's certificate against the mint's pubkey, offline.
 *
 * A certificate is bound to the mint: it signs
 * `LNURLcash:<domain>:<amount_msat>:<hex(Q)>`, so [domain] (the note URL, the
 * mint URL or a bare host; only its lowercase hostname counts) is part of the
 * check. A certificate issued for another mint verifies as `false`. One a mint
 * signed before luds `92abbb3`, which names no domain, still verifies.
 *
 * Accepts the recovery id at either end of the signature, because
 * implementations disagree about which end it belongs on. Trying both is safe:
 * the wrong ordering recovers an unrelated key that cannot match.
 *
 * [k1] is a 64-hex bearer preimage or a `ck1`; its id is found locally, so
 * checking one needs no network either. [signatureHex] may be a current
 * amount-bearing `cs1`, a legacy fixed-prefix `cs1`, or the same 65 bytes as
 * hex. Decode the certificate separately when its carried amount is needed.
 * A k1 that is neither 64 hex nor a `ck1` that verifies has no id to check,
 * and is a plain `false`.
 */
public fun verifyNoteSignature(
    k1: String,
    domain: String,
    amountMsat: Long,
    signatureHex: String,
    mintPubkeyHex: String,
): Boolean = coreVerifyNoteSignature(k1, domain, amountMsat.toULong(), signatureHex, mintPubkeyHex)

/**
 * The raw CURRENT certificate check over exactly the 64-hex id given: a note's
 * output key `hex(Q)` (what [decodeCp1] gives for a `cp1`), at [domain].
 *
 * For checking a certificate without the secret that spends the note. A
 * watcher holding only a share's `cx1` never has that secret, and a wallet
 * that named an output with [LnurlcashClient.rotateWithHash] only needs the
 * output it named. Unlike [verifyNoteSignature] it does not fall back to the
 * certificates a mint signed before the domain was bound.
 */
public fun verifyNoteSignatureHash(
    h: String,
    domain: String,
    amountMsat: Long,
    signatureHex: String,
    mintPubkeyHex: String,
): Boolean = coreVerifyNoteSignatureHash(h, domain, amountMsat.toULong(), signatureHex, mintPubkeyHex)

// ---- LUD-25 notes keyed by a public key, and LUD-26 derivation ----
//
// Every note is a taproot output key `Q`, written `cp1...`. The holder keeps
// the secret key behind it and the service only ever sees `Q`. To spend the
// note the holder hands over `ck1...`: `Q` followed by a BIP-340 Schnorr
// signature by that key over the BIP-341 sighash of a canonical spend whose
// prevout is bound to the mint's domain. A `ck1` therefore spends at one mint
// only, which is why signing and verifying one take the domain. The mint
// certifies each note with `cs1...`, a signature over
// `LNURLcash:<domain>:<amount_msat>:<hex(Q)>`, so a recipient can check a note
// offline with nothing but its `ck1`, `cs1` and the mint's host.
//
// LUD-26 says how a wallet derives those keys from a seed: plain BIP-32 below
// a per-mint root `S` ([deriveServiceRoot]), wallet notes at `S/a'/0/i`,
// change at `S/a'/1/i` and share notes at `S/a'/2/n'/i` ([deriveNoteKey]). A
// share is exported as a 65-byte `cx1` (chain code, then compressed key) which
// a mint turns into note keys by public derivation ([deriveNotePubkey]).
//
// Raw bytes cross as hex, as everywhere else here, and the four bech32m
// strings as themselves. A note secret key, a `ck1`, a service root, a share
// node and a Nostr cash seed are all bearer material: store them the way notes
// are stored, and never log them. A `cx1` spends nothing, but links every note
// on its share.
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
 * A 96-byte key-path spend, the 32-byte output key `Q` followed by its 64-byte
 * BIP-340 signature (what [signNoteOwnership] returns), as a `ck1`. Only the
 * 96-byte shape is encoded; a legacy 65-byte one is refused.
 *
 * That string spends the note, at the mint it was signed for, so it is as
 * secret as the key that made it.
 */
public fun encodeCk1(signatureHex: String): String = coreEncodeCk1(signatureHex)

/**
 * The bytes inside a `ck1`, as hex, or null for anything that is not one: the
 * 96-byte `Q || sig` payload, or the 65 bytes of a legacy recoverable-ECDSA
 * `ck1`, still read so an old note can be rotated. Decoding does not verify;
 * [recoverNoteOwnershipPubkey] does.
 */
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
 * A share as a `cx1`: its chain code followed by its 33-byte compressed public
 * key (BIP-32's order), 65 bytes in all.
 *
 * Whoever holds one can derive every note key on the share, and so link every
 * note on it to the others, but can spend none of them. That is what lets a
 * mint holding a registered `cx1` pay straight to the holder's next key, and it
 * is also why handing one out is a privacy decision.
 *
 * The argument order is the reverse of the pre-LUD-26 [encodeCx1], and the key
 * is now the compressed point rather than x alone. A key that is not a point on
 * the curve is refused.
 */
public fun encodeCx1(chainCodeHex: String, pubkeyHex: String): String =
    coreEncodeCx1(chainCodeHex, pubkeyHex)

/** [encodeCx1] of a [Cx1]. */
public fun encodeCx1(cx1: Cx1): String = coreEncodeCx1(cx1.chainCode, cx1.pubkey)

/**
 * Both halves of a `cx1`, or null for anything that is not 65 bytes of
 * `chainCode || P` with `P` a curve point. The 64-byte shape LUD-25 used
 * before LUD-26 is refused.
 */
public fun decodeCx1(value: String): Cx1? = coreDecodeCx1(value)?.toKotlin()

public fun isCx1(value: String): Boolean = coreIsCx1(value)

/**
 * `S = m/139'/d1/d2/d3/d4` for one mint, from the wallet's `m/139'` root
 * ([deriveCashRoot]), as `privateKey || chainCode` hex.
 *
 * [domain] may be any URL or host; it is reduced to its lowercase hostname
 * with no port first, the same string every signature at that mint commits to.
 * A development mint on `localhost:3338` therefore shares a tree with
 * `localhost`.
 *
 * Bearer material for every note the wallet holds at that mint.
 */
public fun deriveServiceRoot(rootHex: String, domain: String): String =
    coreDeriveServiceRoot(rootHex, domain)

/**
 * The keys of the note at [path] below a service root [serviceRootHex] (from
 * [deriveServiceRoot]): wallet notes `S/a'/0/i`, change `S/a'/1/i`, share
 * notes `S/a'/2/n'/i`. The secret key is bearer material.
 *
 * [NoteKey.outputKey] is `Q`, the note itself: [encodeCp1] of it is what a
 * wallet names as an output, and [signNoteOwnership] of [NoteKey.secretKey]
 * spends it.
 */
public fun deriveNoteKey(serviceRootHex: String, path: NotePath): NoteKey =
    coreDeriveNoteKey(serviceRootHex, path.toFfi()).toKotlin()

/** [deriveNoteKey] of [NotePath.Wallet]: the account's `index`th receiving note. */
public fun deriveWalletNoteKey(serviceRootHex: String, account: Int, index: Int): NoteKey =
    deriveNoteKey(serviceRootHex, NotePath.Wallet(account, index))

/** [deriveNoteKey] of [NotePath.Change]: the account's `index`th change note. */
public fun deriveChangeNoteKey(serviceRootHex: String, account: Int, index: Int): NoteKey =
    deriveNoteKey(serviceRootHex, NotePath.Change(account, index))

/** [deriveNoteKey] of [NotePath.Share]: the `index`th note on one exported share. */
public fun deriveShareNoteKey(serviceRootHex: String, account: Int, share: Int, index: Int): NoteKey =
    deriveNoteKey(serviceRootHex, NotePath.Share(account, share, index))

/**
 * `S/account'/2/share'` as `privateKey || chainCode` hex: the node whose
 * [cashNodeToCx1] a wallet exports to a mint, and whose private key signs that
 * share's registration proofs ([signAddressProof]). Bearer material.
 */
public fun deriveShareNode(serviceRootHex: String, account: Int, share: Int): String =
    coreDeriveShareNode(serviceRootHex, checkedHardenedIndex(account, "account"), checkedHardenedIndex(share, "share"))

/**
 * The note key at [index] on the share a `cx1` exports, by public derivation
 * (BIP-32 CKDpub): what a mint credits a registered address with next, and
 * what a watcher holding only the `cx1` computes. Equals [NoteKey.outputKey]
 * of [deriveShareNoteKey] for the same share.
 *
 * [index] is any `UInt` and is never hardened. An index the derivation cannot
 * use is an error rather than reduced: use the next one. The odds are around
 * 2^-128, which is why this is worth saying and not worth designing around.
 */
public fun deriveNotePubkey(cx1: String, index: UInt): String = coreDeriveNotePubkey(cx1, index)

/**
 * Java-callable overload of [deriveNotePubkey], with [index] in `0..4294967295`.
 *
 * @throws IllegalArgumentException if [index] is outside the unsigned 32-bit range.
 */
public fun deriveNotePubkey(cx1: String, index: Long): String =
    deriveNotePubkey(cx1, checkedNoteIndex(index))

private fun checkedNoteIndex(index: Long): UInt {
    require(index in 0L..0xFFFF_FFFFL) { "index must be in 0..4294967295" }
    return index.toUInt()
}

private fun checkedHardenedIndex(value: Int, what: String): UInt {
    require(value >= 0) { "$what must be in 0..2147483647" }
    return value.toUInt()
}

/**
 * The 96-byte `Q || sig` key-path spend of the note whose secret key is
 * [secretKeyHex], as hex: a BIP-340 Schnorr signature over the BIP-341 sighash
 * of the canonical spend at [domain] (a note URL, a mint URL or a bare host),
 * with the note's output key `Q` attached. [encodeCk1] it for the wire. Either
 * way, it spends the note, at that mint only.
 *
 * [secretKeyHex] is a [NoteKey.secretKey]. The signer negates it when its
 * point has odd y, as BIP-340 requires; `Q` is the x-only key either way.
 * All-zero auxiliary randomness, so one key at one domain always reproduces
 * one `ck1` byte for byte: a wallet restored from its seed can re-sign every
 * note it ever held. Deterministic is not unique, though. See [noteIdOf].
 */
public fun signNoteOwnership(secretKeyHex: String, domain: String): String =
    coreSignNoteOwnership(secretKeyHex, domain)

/**
 * A register, update or unregister proof by a share's own key, as the raw
 * 64-byte BIP-340 signature in hex. [shareSecretKeyHex] is the private key of
 * [deriveShareNode] (its first 64 hex characters).
 *
 * The proof is bound to the service and to what it authorises, so it cannot be
 * replayed elsewhere. It signs `sha256` of:
 *
 * - `register`: `LNURLcash:register:<domain>:<username>:<cx1>`
 * - `update`: `LNURLcash:update:<domain>:<username>:<new cx1>`, signed by the
 *   key on file; a PUT also carries a `register` proof by the new share
 * - `unregister`: `LNURLcash:unregister:<domain>:<username>`, with no [cx1]
 *
 * [username] is lowercased and [domain] reduced to its hostname before
 * signing. [cx1] is the `cx1` string being put on file, required for
 * `register` and `update` and refused for `unregister`.
 */
public fun signAddressProof(
    shareSecretKeyHex: String,
    action: String,
    domain: String,
    username: String,
    cx1: String? = null,
): String = coreSignAddressProof(shareSecretKeyHex, action, domain, username, cx1)

/**
 * What a service checks: [signatureHex] is a proof by [signerCx1]'s key (the
 * `cx1` on file for `update` and `unregister`, the new one for `register`)
 * over this action's message. False for a malformed proof, another action,
 * domain, username or `cx1`.
 */
public fun verifyAddressProof(
    signerCx1: String,
    signatureHex: String,
    action: String,
    domain: String,
    username: String,
    cx1: String? = null,
): Boolean = coreVerifyAddressProof(signerCx1, signatureHex, action, domain, username, cx1)

/**
 * The note's x-only output key from a `ck1` payload, checked offline against
 * the mint at [domain], or null for anything that does not verify there,
 * including a spend signed for another mint.
 *
 * A 96-byte payload is verified against the current domain-bound sighash
 * first, then against the schemes before it (the fixed `sha256("LNURLcash")`
 * message and the raw message), so a note minted under those stays redeemable
 * until it is rotated; [NoteOwner.legacy] says which it was. A legacy 65-byte
 * recoverable-ECDSA payload is read by recovering its key. [signNoteOwnership]
 * never produces either older shape; this only reads them back.
 *
 * This is what a service does with a `ck1` to find the note: [decodeCk1] then
 * this gives the key the note is filed under.
 */
public fun recoverNoteOwnershipPubkey(signatureHex: String, domain: String): NoteOwner? =
    coreRecoverNoteOwnershipPubkey(signatureHex, domain)?.toKotlin()

/**
 * The id a service files a note under: `sha256(k1)` for a Part 1 secret, the
 * verified key a `ck1` embeds for a Part 2 note, and null for anything else,
 * an invalid `ck1` included.
 *
 * Compare notes by this, never by k1. One Part 2 note can have more than one
 * valid `ck1` string: a signer using other auxiliary randomness makes a
 * different signature, and a note minted before the 2026-09-16 digest change
 * carries a raw-message proof. A wallet deduplicating by string would hold
 * one note twice.
 */
public fun noteIdOf(k1: String): String? = coreNoteIdOf(k1)

/**
 * What to look a note up by without disclosing it: the hash for a Part 1
 * secret, the `cp1` for a Part 2 note. Pass it to [buildNoteInfoUrlByHash].
 */
public fun noteLookupOf(k1: String): String? = coreNoteLookupOf(k1)

/** The watch-only half of a share node ([deriveShareNode]): its `cx1` string, what a mint or a watcher is given. */
public fun cashNodeToCx1(nodeHex: String): String = coreCashNodeToCx1(nodeHex)

/**
 * Not LUD-26: the cash seed of a Nostr identity key,
 * `HMAC-SHA256(key = the secret key, msg = "LNURLcash/nostr-seed")`. Bearer
 * material for every note on the identity's tree.
 */
public fun deriveNostrCashSeed(secretKeyHex: String): String = coreDeriveNostrCashSeed(secretKeyHex)

/**
 * Not LUD-26: one mint's service root `S` for a Nostr identity key, as a
 * 64-byte hex node: the same LUD-26 tree as [deriveServiceRoot], rooted in the
 * identity's cash seed instead of a BIP-39 seed. Derive notes and shares from
 * it with [deriveNoteKey] and [deriveShareNode] as usual.
 *
 * A holder with no BIP-39 words (a hardware signer that keeps only its
 * identity key, or a wallet that never made any) can still be paid to keys of
 * its own, and whoever can restore the identity key can rebuild every note
 * paid to it, with or without the device that received them. A mint sees an
 * ordinary `cx1` either way. Replaces `deriveNostrAddressNode`.
 *
 * Bearer material.
 */
public fun deriveNostrServiceRoot(secretKeyHex: String, domain: String): String =
    coreDeriveNostrServiceRoot(secretKeyHex, domain)

// ---- superseded derivation, read only ----
//
// What a wallet minted under before LUD-26 derived its keys with: a per-note
// tweak below a branch node. For finding notes to sweep and rotate into a
// LUD-26 key, never for minting.

/** The old scheme's purpose for wallet notes. */
public const val LEGACY_PURPOSE_WALLET: UInt = 0u

/** The old scheme's purpose for change notes. */
public const val LEGACY_PURPOSE_CHANGE: UInt = 1u

/** The old scheme's purpose for Lightning Address notes. */
public const val LEGACY_PURPOSE_LIGHTNING_ADDRESS: UInt = 2u

/**
 * Superseded: the node the old per-note tweak hung off, `m/139'/d1..d4` over
 * [host] as stored, port included. Bearer material, for sweeping only.
 */
public fun legacyBranchNode(rootHex: String, host: String): String = coreLegacyBranchNode(rootHex, host)

/** Superseded: a [legacyBranchNode]'s 64-byte `cx1` string (x-only key, then chain code). */
public fun legacyCx1Of(nodeHex: String): String = coreLegacyCx1Of(nodeHex)

/**
 * Superseded: the output key `Q` of an old note, from a [legacyCx1Of] string.
 * [purpose] is one of the `LEGACY_PURPOSE_*` constants, or null for the scheme
 * before purposes existed. Look it up at the mint; it is not a LUD-26 key.
 */
public fun legacyDeriveNotePubkey(legacyCx1: String, purpose: UInt?, index: UInt): String =
    coreLegacyDeriveNotePubkey(legacyCx1, purpose, index)

/** Superseded: the secret key behind [legacyDeriveNotePubkey], from a [legacyBranchNode]. Bearer material. */
public fun legacyDeriveNoteSecretKey(nodeHex: String, purpose: UInt?, index: UInt): String =
    coreLegacyDeriveNoteSecretKey(nodeHex, purpose, index)

/**
 * Resolve scanned or pasted text to a note URL - bech32, `lnurlw://`, or https.
 *
 * The URL's k1 must be 32 bytes of hex or a Part 2 `ck1` that verifies. A
 * `cp1` there is refused: it names the note but cannot spend it.
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

internal fun FfiMintFee.toKotlin(): MintFee = MintFee(
    baseFeeMsat = baseFeeMsat.toLong(),
    feePpm = feePpm.toLong(),
    addressFeeMsat = addressFeeMsat?.toLong(),
)

internal fun MintFee.toFfi(): FfiMintFee = FfiMintFee(
    baseFeeMsat = baseFeeMsat.toULong(),
    feePpm = feePpm.toULong(),
    addressFeeMsat = addressFeeMsat?.toULong(),
)

internal fun FfiCx1.toKotlin(): Cx1 = Cx1(chainCode = chainCode, pubkey = pubkey)

internal fun FfiNoteKey.toKotlin(): NoteKey = NoteKey(secretKey = secretKey, pubkey = pubkey, outputKey = outputKey)

internal fun FfiNoteOwner.toKotlin(): NoteOwner = NoteOwner(pubkeyXOnly = pubkeyXOnly, legacy = legacy)

internal fun NotePath.toFfi(): FfiNotePath = when (this) {
    is NotePath.Wallet -> FfiNotePath.Wallet(account.toUInt(), index.toUInt())
    is NotePath.Change -> FfiNotePath.Change(account.toUInt(), index.toUInt())
    is NotePath.Share -> FfiNotePath.Share(account.toUInt(), share.toUInt(), index.toUInt())
}

internal fun FfiCs1.toKotlin(): Cs1 = Cs1(amountMsat = amountMsat.toLong(), signature = signature)
