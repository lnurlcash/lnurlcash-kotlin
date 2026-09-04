package io.github.thecryptodonkey.lnurlcash

import uniffi.lnurlcash_core.FfiMintFee
import uniffi.lnurlcash_core.applyMintFee as coreApplyMintFee
import uniffi.lnurlcash_core.buildNoteUrl as coreBuildNoteUrl
import uniffi.lnurlcash_core.decodeBolt11AmountMsat as coreDecodeBolt11AmountMsat
import uniffi.lnurlcash_core.describeMintFee as coreDescribeMintFee
import uniffi.lnurlcash_core.buildNoteInfoUrlByHash as coreBuildNoteInfoUrlByHash
import uniffi.lnurlcash_core.cashDomainIndices as coreCashDomainIndices
import uniffi.lnurlcash_core.cashSecretAt as coreCashSecretAt
import uniffi.lnurlcash_core.deriveCashDomainNode as coreDeriveCashDomainNode
import uniffi.lnurlcash_core.deriveCashRoot as coreDeriveCashRoot
import uniffi.lnurlcash_core.deriveCashSecret as coreDeriveCashSecret
import uniffi.lnurlcash_core.deriveNoteRoot as coreDeriveNoteRoot
import uniffi.lnurlcash_core.deriveNoteSecret as coreDeriveNoteSecret
import uniffi.lnurlcash_core.generateNoteSecret as coreGenerateNoteSecret
import uniffi.lnurlcash_core.grossUpForMintFee as coreGrossUpForMintFee
import uniffi.lnurlcash_core.hashK1 as coreHashK1
import uniffi.lnurlcash_core.isAllowedServiceUrl as coreIsAllowedServiceUrl
import uniffi.lnurlcash_core.isBolt11Invoice as coreIsBolt11Invoice
import uniffi.lnurlcash_core.isPreimage as coreIsPreimage
import uniffi.lnurlcash_core.mintAddressUrl as coreMintAddressUrl
import uniffi.lnurlcash_core.noteDeclaredAmount as coreNoteDeclaredAmount
import uniffi.lnurlcash_core.noteK1 as coreNoteK1
import uniffi.lnurlcash_core.noteSignature as coreNoteSignature
import uniffi.lnurlcash_core.parseMintFee as coreParseMintFee
import uniffi.lnurlcash_core.resolveLnurlInput as coreResolveLnurlInput
import uniffi.lnurlcash_core.resolveMintInput as coreResolveMintInput
import uniffi.lnurlcash_core.resolveNoteInput as coreResolveNoteInput
import uniffi.lnurlcash_core.sameInvoice as coreSameInvoice
import uniffi.lnurlcash_core.verifyNoteSignature as coreVerifyNoteSignature
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
 * LUD-25 seed-recoverable note secrets: `m/139'`, this wallet's own root.
 *
 * `seedHex` is raw seed bytes as hex; a 64-byte BIP39 seed is the interop
 * case. The returned node is `privateKey || chainCode`, 64 bytes of hex, and
 * is bearer material for every note beneath it.
 *
 * The path is `m/139'/d1/d2/d3/d4/i'`, where `d1..d4` are RAW uint32 out of
 * `HMAC-SHA256(m/139'/0, host)`. BIP-32 reads any index at or above 2^31 as
 * hardened, so which of those levels are hardened is decided by the mint's own
 * host name. Masking the top bit, or hardening all four, derives a different
 * tree from every conforming wallet and restores nothing, silently.
 */
public fun deriveCashRoot(seedHex: String): String = coreDeriveCashRoot(seedHex)

/**
 * `m/139'/d1/d2/d3/d4` for one mint: everything above a note's own index.
 *
 * Every unhardened level sits at or above this node, so a hardware signer
 * provisioned with it rather than the seed needs no elliptic curve at all.
 * The cost is that whoever derives it can derive every note secret held at
 * that mint: provisioning material, one mint's subtree, not the wallet.
 */
public fun deriveCashDomainNode(rootHex: String, host: String): String =
    coreDeriveCashDomainNode(rootHex, host)

/** The i-th note secret beneath a mint's domain node. */
public fun cashSecretAt(domainNodeHex: String, index: UInt): String =
    coreCashSecretAt(domainNodeHex, index)

/**
 * The i-th note secret at a mint, from the root.
 *
 * Re-derives the domain node on every call. Hold the node for a run of
 * secrets, and persist the index in the SAME write that stages the record,
 * BEFORE its hash goes on the wire.
 */
public fun deriveCashSecret(rootHex: String, host: String, index: UInt): String =
    coreDeriveCashSecret(rootHex, host, index)

/** The four raw uint32 levels a mint's subtree hangs off. */
public fun cashDomainIndices(rootHex: String, host: String): List<UInt> =
    coreCashDomainIndices(rootHex, host)

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
 */
public fun verifyNoteSignature(
    k1: String,
    amountMsat: Long,
    signatureHex: String,
    mintPubkeyHex: String,
): Boolean = coreVerifyNoteSignature(k1, amountMsat.toULong(), signatureHex, mintPubkeyHex)

/** Resolve scanned or pasted text to a note URL - bech32, `lnurlw://`, or https. */
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
