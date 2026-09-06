package io.github.thecryptodonkey.lnurlcash

import uniffi.lnurlcash_core.LnurlcashException

/**
 * What a service says a note is worth, and how to spend it.
 *
 * [maxWithdrawableMsat] is the only authoritative statement of value. The
 * `amount` in a note URL is a claim by whoever encoded it, and the service
 * ignores it.
 */
public data class NoteInfo(
    public val callback: String,
    public val k1: String,
    public val maxWithdrawableMsat: Long,
    public val minWithdrawableMsat: Long,
    public val defaultDescription: String?,
    /** Present when the service signs its notes, enabling offline verification. */
    public val mintPubkey: String?,
)

/** A note this wallet now holds, whose secret the service has never seen. */
public data class RotatedNote(
    public val k1: String,
    public val signature: String?,
)

/** The two notes a split produced: the amount asked for, and the change. */
public data class SplitNotes(
    public val k1: String,
    public val change: String,
    public val signature: String?,
    public val changeSignature: String?,
)

/**
 * A melt that the service has accepted.
 *
 * This means the payment is IN FLIGHT. It does not mean the note is spent: the
 * service pays asynchronously and only burns the note once the payment settles,
 * restoring it if the payment fails. A failed melt is never reported back - it
 * is only observable as the note becoming spendable again.
 */
public data class MeltReceipt(
    public val invoice: String?,
    /** A LUD-21 style URL proving this exact payment settled, if offered. */
    public val verifyUrl: String?,
)

/** A note that was settled against what the service says it is really worth. */
public data class SettledNote(
    public val k1: String,
    public val amountMsat: Long,
    public val signature: String?,
    public val callback: String,
)

public data class MintFee(
    public val baseFeeMsat: Long,
    public val feePpm: Long,
)

public data class PayRequest(
    public val callback: String,
    public val minSendableMsat: Long,
    public val maxSendableMsat: Long,
    public val metadata: String,
    /**
     * Present when paying this mints a bearer note: the raw LUD-17 withdraw
     * endpoint, in either the plain or the `lnurlw://` spelling. The draft says
     * "as described in LUD-17", and LUD-17 describes both, so a wallet accepts
     * either unchanged.
     */
    public val withdrawLink: String?,
    public val mintPubkey: String?,
    /** Absent means the service advertised no fee, which the spec reads as fee-free. */
    public val mintFee: MintFee?,
    /**
     * LUD-12's field, and LUD-25's minting capability. A mint must allow the 64
     * characters a hex-encoded SHA-256 commitment needs.
     */
    public val commentAllowed: Long?,
    /**
     * Additive ForgeSworn extension: the service also accepts the same
     * commitment as an `h` parameter. Never a substitute for the mandatory
     * comment, and anything that is not exactly true reads as false.
     */
    public val mintToHash: Boolean,
    /**
     * Whether this service can mint a current-draft LUD-25 note.
     *
     * [mintToHash] alone cannot stand in for it: that extension is additive and
     * predates the comment spelling, and a service without the comment capacity
     * has nowhere to put the commitment.
     */
    public val namesMintOutput: Boolean,
)

public data class Invoice(
    public val invoice: String,
    public val verifyUrl: String?,
    /** LUD-11: absent on the wire MUST be read as true. */
    public val disposable: Boolean,
)

public data class InvoiceStatus(
    public val settled: Boolean,
    /**
     * Settlement proof, not the note. Current LUD-25 binds a note to a
     * wallet-chosen secret named in the mint comment, so a preimage here
     * redeems nothing. (An earlier draft keyed the note by this value, which
     * made every routing node that forwarded the payment a holder of it.)
     */
    public val preimage: String?,
    public val invoice: String,
)

public data class MintAddress(
    public val callback: String,
    public val payLink: String,
    public val maxWithdrawableMsat: Long,
    public val minWithdrawableMsat: Long,
    public val nodePubkey: String?,
    public val nodeAlias: String?,
    public val nodeUri: String?,
    public val nodeColor: String?,
    /**
     * The wire field is `nodeCapacity`, msat like every other amount here.
     * Suffixed on this side so a caller cannot read it as sats.
     */
    public val nodeCapacityMsat: Long?,
    public val nodeNumChannels: Long?,
    public val nodeNumPeers: Long?,
    /**
     * Every address the service's node announces, each already
     * `node_key@host:port`. [nodeUri] is the first of them; a node behind Tor
     * as well as clearnet has more, and a caller that can only reach the other
     * one needs the whole list. Null, never an empty list, when the service
     * announces nothing.
     */
    public val nodeUris: List<String>?,
    /**
     * The day the service plans to close, ISO-8601 (`"2026-12-31"`). Advance
     * warning while there is still time to spend, deliberately not the same
     * thing as a mint that has already stopped minting. Nothing enforces it
     * and nothing verifies it, so it is a prompt to move notes, never a
     * deadline to compute against - and it is a plain string rather than a
     * date type for exactly that reason.
     */
    public val sunsetDate: String?,
    /**
     * What the service says it owes, msat: every note it has issued and not
     * burned. Its own claim about its own database, with nothing to check it
     * against, so read it next to what the node holds rather than on its own.
     * `0` and null are different answers.
     */
    public val outstandingNotesMsat: Long?,
)

/** What a probe learned about a note whose fate was uncertain. */
public enum class NoteFate {
    /** Still outstanding: the request never landed, so the fresh secrets minted nothing. */
    LIVE,

    /** Spent or unknown: the burn landed, and the carried secrets are the only money left. */
    GONE,

    /** The probe itself failed. No information either way - keep everything. */
    UNKNOWN,
}

/**
 * The result of an operation that could have burned a note.
 *
 * Mutations return this rather than throwing, because the dangerous case is not
 * an error - it is an *unknown*. A `try`/`catch` invites treating "the answer
 * was lost" as "it did not happen", and for a rotate that already burned the
 * input, that reasoning destroys money the service has already minted.
 *
 * Making [Unknown] a branch the compiler will not let you ignore is the whole
 * reason this wrapper exists.
 */
public sealed interface MutationOutcome<out T> {
    /** The service confirmed it. */
    public data class Confirmed<out T>(public val value: T) : MutationOutcome<T>

    /**
     * The service processed the request and refused it, or the request never
     * left. Definitive: the operation did not happen.
     */
    public data class Rejected(public val error: LnurlcashException) : MutationOutcome<Nothing>

    /**
     * The outcome is not known. The mutation MAY have been applied.
     *
     * [newSecrets] are the fresh secrets the request disclosed the hashes of.
     * **Persist them before doing anything else**, then use
     * [LnurlcashClient.probeBurnedNote] on one of the inputs to find out what
     * happened. Order matches the operation: `[rotated]` for a rotate,
     * `[splitOff, change]` for a split, `[merged]` for a merge.
     */
    public data class Unknown(
        public val newSecrets: List<String>,
        public val message: String,
        public val cause: Throwable? = null,
    ) : MutationOutcome<Nothing>

    /**
     * The service confirmed the mutation but returned no signature over it.
     *
     * LUD-25 makes offline verification mandatory, so this is a non-compliant
     * service - but the mutation **landed**. Its own case rather than a
     * [Rejected], which would say the operation did not happen, and rather
     * than an [Unknown], which would say nobody can tell: the note exists, at
     * the hash the wallet disclosed, and [newSecrets] is the only key to it
     * anywhere. **Persist them before anything else**, then decide whether to
     * keep dealing with a mint that issues notes nobody can check.
     *
     * Only ever produced when the client requires signatures, which is the
     * default.
     */
    public data class Unverifiable(
        public val newSecrets: List<String>,
        public val message: String,
    ) : MutationOutcome<Nothing>
}

/**
 * The confirmed value, or an exception.
 *
 * Convenient, and a deliberate loss of safety: it collapses [MutationOutcome.Unknown]
 * into a throw, and whatever catches it can no longer see the secrets. Only use
 * it where losing the note would not matter, or where the secrets are already
 * saved.
 */
public fun <T> MutationOutcome<T>.getOrThrow(): T = when (this) {
    is MutationOutcome.Confirmed -> value
    is MutationOutcome.Rejected -> throw error
    is MutationOutcome.Unknown -> throw IllegalStateException(
        "$message (fresh secrets: ${newSecrets.size} - these may be the only copy)",
        cause,
    )
    is MutationOutcome.Unverifiable -> throw IllegalStateException(
        "$message (fresh secrets: ${newSecrets.size} - the note EXISTS, these are the only copy)",
    )
}

/** The confirmed value, or null. Same caveat as [getOrThrow]. */
public fun <T> MutationOutcome<T>.getOrNull(): T? =
    (this as? MutationOutcome.Confirmed)?.value
