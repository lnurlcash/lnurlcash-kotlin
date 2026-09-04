# Changelog

Semantic versioning. While the LUD-25 draft is unmerged, `0.x` minor bumps may
carry breaking changes; pin an exact version.

## 0.1.0 — unreleased

### Seed-recoverable note secrets, and the private lookup a restore needs

- `deriveCashRoot`, `deriveCashDomainNode`, `deriveCashSecret`,
  `cashSecretAt`, `cashDomainIndices`: LUD-25's `m/139'` scheme, from the Rust
  core. Nodes cross as 64 bytes of hex - `privateKey || chainCode` - so
  nothing here has to model a BIP-32 key.
- `deriveNoteRoot` / `deriveNoteSecret`: the pre-spec HMAC scheme, so notes
  minted under it stay findable. Not what to mint under.
- `buildNoteInfoUrlByHash`: LUD-25's `?h=` informational GET, which is what a
  restore walk should use. Asking by secret publishes the very indices the
  wallet is about to mint under.
- Bindings regenerated. The new derivation vectors are driven through the
  Kotlin facade rather than the core directly, so the FFI boundary is graded
  too: every value crosses as hex, and a marshalling mistake there would be
  invisible from either side alone.

First release. An idiomatic Kotlin wrapper over
[lnurlcash-core](https://github.com/TheCryptoDonkey/lnurlcash-core), checked
against the shared
[conformance vectors](https://github.com/TheCryptoDonkey/lnurlcash-conformance)
and the adversarial mock mint.

### Design notes

**Offline verification is mandatory, and this library insists on it.** LUD-25
stopped treating a note signature as optional: a service MUST publish
`mintPubkey` and MUST sign every note a rotate, split or merge mints.
`fetchNoteInfo` throws for a `withdrawRequest` publishing no valid one, and a
confirmed-but-unsigned mutation returns the new `MutationOutcome.Unverifiable`.
`LnurlcashClient(requireSignatures = false)` opts out.

`Unverifiable` is its own case rather than a `Rejected`, which would say the
operation did not happen, and rather than an `Unknown`, which would say nobody
can tell. The mutation LANDED: the note exists at the hash the wallet
disclosed, and `newSecrets` is the only key to it. A caller matching on
`MutationOutcome` gets a compiler error until it handles that, which is the
whole point of the sealed interface.

**A mutation whose answer was lost is re-sent, and usually completes.** LUD-25
gained a "Retrying a mutation" section: a service MUST answer a byte-identical
rotate, split or merge with the success it already returned. That closes the
hazard this library's OkHttp dependency was working around, so the client
re-sends deliberately - `mutationRetries`, default one extra attempt.

Never a melt, which carries `pr`, is paid asynchronously and has no replay
guarantee; never a definitive refusal, which is the service's considered
answer; and the same `FfiRequest` goes out each attempt rather than a rebuilt
one, because the replay is matched on the k1 set, `h`, `h2` and `amount` - a
regenerated secret would make the retry a different mutation, and a second real
burn. Transport-level retries stay off regardless: a deliberate retry this
library counts is a different thing from an invisible one it does not.

**Minting is comment-bound, and the payment preimage is only settlement proof.**
The draft keyed a fresh note by the invoice's payment preimage until 31 August
2026, when that fallback was removed outright: a preimage propagates to every
node that forwarded the payment, routinely before the payer has finished
processing it, so a note keyed by one is a note all of them can spend. A WALLET
now chooses the secret itself, before any invoice exists, and hands the SERVICE
only `sha256(secret)` in a mandatory LUD-12 `comment`; a minting `payRequest`
must advertise `commentAllowed >= 64` or it cannot mint at all. `requestMintInvoice` takes the secret the
caller will hold the note by - persist it before paying, because the service
holds nothing that could reconstruct it.

**The mint address carries the node stats under their wire names.** lnurl-mint
advertises `nodeCapacity` in msat, so `nodeCapacityMsat` is a rename and is
mapped explicitly — the TypeScript sibling shipped that rename unmapped and
read null for every mint.



**Mutations return `MutationOutcome`, they do not throw.** The dangerous case
is not an error but an *unknown*, and a `catch` block invites treating "the
answer was lost" as "it did not happen". `MutationOutcome.Unknown` carries the
fresh secrets and is a branch the compiler will not let a caller ignore.

**OkHttp, not `java.net.http`.** The JDK's client retries idempotent GETs on a
mid-flight connection reset and offers no way to switch that off. Every
LNURLcash mutation is a GET that is not idempotent, so a retry gets "already
spent" for its second attempt and reports a definitive rejection — discarding
the fresh secret of a note the service just minted. Found by running the
adversarial mock mint's `dropAfterMutation` mode, which failed two tests until
the transport changed.

**Amounts cross as `Long`, not `ULong`.** The bindings speak `ULong` because
Rust says `u64`; that is awkward from Kotlin and unusable from Java, and 21
million BTC is 2.1e15 msat — about a thousandth of `Long.MAX_VALUE`.

**The bindings are a separate Gradle module.** They are generated, so they
cannot satisfy the explicit-API rules the hand-written wrapper is held to, and
keeping them apart makes the "do not edit" boundary impossible to miss.

**The FFI error field is `detail`, not `message`.** `message` collided with
`Throwable.message` in the generated Kotlin and made every call site ambiguous;
the Rust core was renamed to suit its bindings.
