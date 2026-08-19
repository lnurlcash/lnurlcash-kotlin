package io.github.thecryptodonkey.lnurlcash

import uniffi.lnurlcash_core.FfiMintFee
import uniffi.lnurlcash_core.applyMintFee as coreApplyMintFee
import uniffi.lnurlcash_core.buildNoteUrl as coreBuildNoteUrl
import uniffi.lnurlcash_core.decodeBolt11AmountMsat as coreDecodeBolt11AmountMsat
import uniffi.lnurlcash_core.describeMintFee as coreDescribeMintFee
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
