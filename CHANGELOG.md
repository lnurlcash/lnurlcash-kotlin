# Changelog

Semantic versioning. While the LUD-25 draft is unmerged, `0.x` minor bumps may
carry breaking changes; pin an exact version.

## 0.1.0 — unreleased

### Published to Maven Central

- The jar now carries the native core for linux-x86-64, linux-aarch64,
  darwin-x86-64, darwin-aarch64 and win32-x86-64, under the resource prefixes
  JNA computes, so `implementation("io.github.thecryptodonkey:lnurlcash-kotlin")`
  is all a consumer needs. No sibling checkout, no `jna.library.path`, no
  `cargo` on the machine.
- The bindings module publishes as `lnurlcash-kotlin-bindings` and arrives
  transitively. It was previously rendered into the POM as
  `lnurlcash-kotlin:bindings:unspecified`, a coordinate that does not exist and
  could never have resolved, so the published POM would have been broken on
  arrival.
- `core.sha` pins the exact `lnurlcash-core` commit a release is built from.
  Every native comes from it, and the release regenerates the bindings against
  it and fails on any difference — a binding that disagrees with the library it
  calls is undefined behaviour on a money path, and it is invisible from either
  side alone.
- Publishing refuses a native set with a hole in it. A missing platform is not
  a degraded release, it is an `UnsatisfiedLinkError` for everyone on that
  platform, and a version on Central cannot be withdrawn. Checked twice: once
  against the directory, and once by opening the jar that is about to be
  published, because a file can sit on disk and still not be packaged. The
  second check also rejects anything under a kilobyte, which is what a
  placeholder from a local rehearsal looks like.
- Windows on aarch64 as well, so the only JVM platform not covered is Android,
  which needs its own ABIs and an AAR.
- Real Dokka javadoc for `lnurlcash-kotlin`. Central only requires a javadoc
  artifact to exist, and an empty one helps nobody when `explicitApi()` has
  already forced the public API to be documented.
- **Android.** `lnurlcash-kotlin-android` is an aar carrying the core built
  against the NDK for `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86`, minSdk
  21. Separate from the jar because the two share no native code, and AGP would
  package the desktop `.so`/`.dylib`/`.dll` into every APK as dead java
  resources. It is assembled by a Zip task rather than by AGP, so this build
  still needs no Android SDK, and `android-verify/` proves the result by
  resolving it through AGP and running the FFI on an emulator against the
  conformance vectors.
- Built against Kotlin 2.0 metadata and a Kotlin 2.0 stdlib rather than the
  2.4.10 that compiles it. A Kotlin compiler reads metadata at most one minor
  above its own, so 2.4 metadata locked out every consumer below 2.3 —
  including the Kotlin 2.2 built into AGP 9, which is where most Android
  projects will be. The published stdlib dependency was doing the same damage
  from the other direction: Gradle resolves to the highest version, so a 2.4.10
  stdlib dragged consumers up whether or not they could read it.
- The release can be dry run: it builds all six natives and assembles both
  modules without uploading, so the cross-platform builds are not attempted for
  the first time during a release that cannot be taken back. It needs none of
  the publishing secrets.

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
