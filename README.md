# lnurlcash-kotlin

LNURLcash ([LUD-25 draft](https://github.com/lnurl/luds/pull/301)) bearer notes
for Kotlin and the JVM, over the audited
[Rust core](https://github.com/lnurlcash/lnurlcash-core).

```kotlin
dependencies {
    implementation("com.lnurlcash:lnurlcash-kotlin:0.1.0")
}
```

Early `0.x`, tracking a **draft** spec. Pin an exact version.

The jar carries the native core for every platform a JVM is likely to be on, so
there is nothing to build and no `jna.library.path` to set:

| | x86-64 | aarch64 |
|---|---|---|
| Linux (glibc 2.34+) | yes | yes |
| macOS | yes | yes |
| Windows | yes | yes |

Anywhere else — Alpine and its musl libc, FreeBSD, 32-bit anything — build
[the core](https://github.com/lnurlcash/lnurlcash-core) yourself and
point `-Djna.library.path` at it. The Kotlin side is unchanged either way.

On **Android**, depend on the aar instead. Same API, same vectors, ABIs under
`jni/` where AGP expects them rather than desktop libraries at the root of a
jar:

```kotlin
dependencies {
    implementation("com.lnurlcash:lnurlcash-kotlin-android:0.1.0")
}
```

`arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86`, minSdk 21. Nothing else to
configure: it brings JNA's Android aar with it, so `libjnidispatch.so` is
packaged too.

### Kotlin version

Built against Kotlin 2.0 metadata and a Kotlin 2.0 stdlib, deliberately. A
Kotlin compiler reads metadata up to one minor version above its own, so a
library built with 2.4 cannot be consumed by anything below 2.3 — which
includes the Kotlin 2.2 built into AGP 9. Anything from Kotlin 2.0 upward can
use this, and a consumer on a later Kotlin keeps their own stdlib.

## Read this before you use any LNURLcash library on the JVM

Every LNURLcash mutation — rotate, split, merge, melt — is an HTTP **GET**.
HTTP considers GET idempotent, so clients retry one freely when a connection
fails mid-flight. An LNURLcash mutation is emphatically *not* idempotent: the
first attempt burns the input note.

For most of this draft's life that was fatal. A service applied a rotate, the
connection dropped, a retrying client sent it again, got `"invalid or already
spent k1"` for the second attempt, and reported a **definitive rejection**. The
caller concluded nothing had happened and discarded the fresh secret — which
was the only copy of the note the service had just minted. The money was gone,
and every layer had behaved reasonably.

The JDK's own `java.net.http.HttpClient` does exactly that resend, and offers
no way to switch it off. That is why this library depends on OkHttp and builds
its default client with `retryOnConnectionFailure(false)`. It was not theory:
running this library against a mock mint that hangs up mid-mutation failed two
tests before the transport was changed.

**LUD-25 has since closed the hole at the other end.** A service MUST now
answer a byte-identical rotate, split or merge with the success it already
returned, signature and all. So this library re-sends one whose answer was lost
— deliberately, bounded, and never a melt — and a dropped connection usually
resolves into a `Confirmed` outcome instead of an `Unknown`.

`mutationRetries` sets how many extra attempts (default 1; `0` restores the old
give-up-at-once behaviour). Only rotate, split and merge — never a melt, which
carries `pr`, is paid asynchronously and has no replay guarantee — and only an
ambiguous failure, never a refusal the service actually considered. The re-sent
request is byte-identical, because the replay is matched on the k1 set, `h`,
`h2` and `amount`.

Transport-level retries stay off regardless: a deliberate retry this library
counts is a different thing from an invisible one it does not. **If you supply
your own client, it must have retries disabled.**

## Legacy signatures and cp1 certificates

The reference mint returns a raw Part 1 signature for a legacy hash output
when a signer is available, and may omit it in no-signer mode. This library
accepts that omission by default and preserves any signature received.

A `cp1` output is owed its `cs1` certificate whatever the options say. A
mutation the service confirms without one comes back as
`MutationOutcome.Unverifiable`: its own case, because the mutation **landed**,
the note is real, and whatever stands behind it is the only key to it. Persist
it before anything else.

`fetchNoteInfo` throws for a `withdrawRequest` that publishes no valid
`mintPubkey`, the key a certificate verifies against.

- `LnurlcashClient(requireSignatures = true)` demands the raw Part 1
  signature over a hash output, matching the committed reference wallet.
  An unsigned one is then `Unverifiable` too, carrying the fresh secrets.
- `LnurlcashClient(requireMintPubkey = false)` admits a Part 1-only service
  that publishes no `mintPubkey`.

If you want a note a recipient can check offline, hold a `cp1` note.

## Usage

```kotlin
val client = LnurlcashClient()

val url = resolveNoteInput(scanned) ?: error("not a note")
val info = client.fetchNoteInfo(url)          // what is it actually worth?
println("${info.maxWithdrawableMsat} msat")

when (val outcome = client.rotate(info.callback, info.k1)) {
    is MutationOutcome.Confirmed -> save(outcome.value.k1)
    is MutationOutcome.Rejected  -> show(outcome.error)
    is MutationOutcome.Unknown   -> {
        save(outcome.newSecrets)               // first. always.
        when (client.probeBurnedNote(url)) {
            NoteFate.LIVE    -> {}             // nothing landed; those secrets are worthless
            NoteFate.GONE    -> {}             // the burn landed; those secrets ARE the note
            NoteFate.UNKNOWN -> {}             // keep everything, try again later
        }
    }
    // landed, without a signature it was owed: only under requireSignatures
    // for this plain rotate, and always for a cp1 output left uncertified
    is MutationOutcome.Unverifiable -> save(outcome.newSecrets)
}
```

That `when` is the reason this wrapper exists. Mutating operations return
[`MutationOutcome`](src/main/kotlin/com/lnurlcash/Types.kt)
rather than throwing, because the dangerous case is not an error — it is an
*unknown*. A `try`/`catch` invites treating "the answer was lost" as "it did
not happen", and for a rotate that already burned the input, that reasoning
destroys money. Making `Unknown` a branch the compiler will not let you ignore
is the whole point.

`getOrThrow()` exists for when you genuinely do not care, and says so.

## Notes keyed by a public key (LUD-25 Part 2)

A Part 2 note swaps the hash for a key pair. The wallet keeps `sk`, and the
mint only ever sees `pk`, written `cp1...`. To spend the note you hand over
`ck1...`, a recoverable signature by `sk` over the fixed message `LNURLcash`,
and the mint recovers `pk` from it to find the note. The mint's certificate,
`cs1...`, carries the note amount in its prefix using BOLT 11 amount rules and
contains the signature over `LNURLcash:<amount_msat>:<hex(pk)>`. A recipient
can therefore recover both the claimed amount and the mint signature before
checking the note offline.

```kotlin
val node = deriveCashAddressNode(deriveCashRoot(seedHex), "mint.example") // bearer material
val branch = cashNodeToCx1(node)
val cx1 = encodeCx1(branch.pubkeyXOnly, branch.chainCode)    // watch-only

val i = 0u                                                // unsigned note index
val pk = deriveNotePubkey(branch.pubkeyXOnly, branch.chainCode, i) // what a watcher derives
val sk = deriveNoteSecretKey(node.take(64), node.drop(64), i)
val ck1 = encodeCk1(signNoteOwnership(sk))                   // the bearer secret

// persist i (or sk) FIRST: this library never sees what stands behind an output
client.rotateWithHash(info.callback, oldK1, encodeCp1(pk))

verifyNoteSignature(ck1, amountMsat, cs1, mintPubkey)        // offline
val certificate = decodeCs1WithAmount(cs1)!!                 // amount + signature
```

`encodeCs1WithAmount`, `decodeCs1WithAmount` and
`isCs1WithAmount` are the current wire API. The fixed-prefix `encodeCs1`,
`decodeCs1` and `isCs1` remain for legacy notes; `decodeAnyCs1` and `isAnyCs1`
are migration helpers. Verification accepts either form, matching the reference
kit; decode the certificate separately if the application needs to compare its
carried amount with another value.

Reference-mint address management proves control with the address branch's
index-0 private key. `signAddressProof(sk0, action, username)` returns the raw
`r || s || recovery-id` proof over `LNURLcash:<action>:<username>`; action is
`register` or `unregister`, and the username must be normalised exactly as it
is sent to the service.

From Java, the two note derivation functions have overloads taking a `long`
index in `0..4294967295`; values outside that range throw
`IllegalArgumentException`. The existing Kotlin `UInt` overloads remain available.

```java
String node = LnurlcashKt.deriveCashAddressNode(cashRoot, "mint.example");
Cx1 branch = LnurlcashKt.cashNodeToCx1(node);
long index = 0L;
String pk = LnurlcashKt.deriveNotePubkey(branch.getPubkeyXOnly(), branch.getChainCode(), index);
String sk = LnurlcashKt.deriveNoteSecretKey(node.substring(0, 64), node.substring(64), index);
```

The wire takes both kinds. A `ck1` goes anywhere a k1 does. A `cp1` goes
anywhere an output does: `requestMintInvoiceWithHash` sends it as the comment
alone, `rotateWithHash`, `splitWithHash` and `mergeWithHash` send it as
`p1`/`p2` where a hash keeps `h`/`h2`, and `buildNoteInfoUrlByHash` sends it as
`p` where a hash keeps `h`.

`noteIdOf(k1)` is the id a mint files either kind under, and `noteLookupOf(k1)`
what to look a note up by without disclosing it. One note has more than one
valid `ck1` (anyone can flip one to its high-S twin), so compare notes by id,
never by k1. `fetchNoteInfo` compares a mint's echo that way too.

Three things worth knowing:

- **The `WithHash` mutations carry no secrets.** They never see the key behind
  the output, so an `Unknown` or `Unverifiable` from one hands nothing back.
  Save it before the call.
- **The branch path follows the reference wallet, not the draft's text.** It is
  `m/139'/1'/d1/d2/d3/d4`. The text says `m/139'/d1..d4`, which is the Part 1
  ladder's own node, and a wallet following it finds none of lnurl-wallet's
  notes.
- **A `cx1` links every note on its branch.** It spends nothing, but whoever
  holds it can list every key on the branch and ask the mint about each one.

`deriveNostrAddressNode(secretKey, host)` roots a branch in a Nostr identity
key, for a holder with no BIP-39 words. That one is an extension, not LUD-25; a
mint sees an ordinary `cx1` either way.

## What is in Rust and what is in Kotlin

The money-critical logic — request building, response classification, signature
verification, fee arithmetic — lives in
[lnurlcash-core](https://github.com/lnurlcash/lnurlcash-core) and is
shared with the Swift bindings. One audited implementation, not a hand-written
port drifting away from a draft spec.

Kotlin owns the HTTP, the coroutines, and the types. The bindings themselves
are generated by UniFFI into the `:bindings` module and are never edited by
hand — `scripts/generate-bindings.sh` regenerates them, and CI diffs the result.

Amounts cross as `Long`, not the bindings' `ULong`: `ULong` is awkward from
Kotlin and unusable from Java, and 21 million BTC is 2.1e15 msat — about a
thousandth of `Long.MAX_VALUE`.

## The other three things that will cost you money

**Never let the service generate a replacement secret.** On rotate, split and
merge this library draws a fresh 32 bytes and discloses only `sha256(secret)`.
A service-issued replacement has, structurally, been seen by that service.

**A melt's confirmation means "in flight", not "spent".** The service pays
asynchronously and only burns the note once the payment settles, restoring it
if the payment fails. A failed melt is never reported back — only observed as
the note becoming spendable again. `LnurlcashException.NotePending` means
retry, never spent.

**Rotate the instant you claim a minted note.** The preimage that mints a note
is generated by the service, and if it serves LUD-21 `verify`, anyone who saw
the unpaid invoice can poll for it. First rotater wins.

## Building

```bash
./scripts/build-core.sh            # cargo build --release --features ffi
./scripts/generate-bindings.sh     # regenerate the UniFFI bindings
gradle build
```

Tests need `node` and the
[conformance repo](https://github.com/lnurlcash/lnurlcash-conformance)
alongside this one, or `LNURLCASH_CONFORMANCE` pointing at it — and that
checkout needs its own `npm ci`, because the adversarial mock mint is a Node
process with dependencies of its own:

```bash
git clone https://github.com/lnurlcash/lnurlcash-conformance ../lnurlcash-conformance
(cd ../lnurlcash-conformance && npm ci)
```

They run the same vectors as the TypeScript, Python, Rust and Go
implementations, plus the adversarial mock mint.

Releases are their own thing; see [RELEASING.md](RELEASING.md).

## Android

`lnurlcash-kotlin-android` is a real aar: the same Kotlin, plus the core built
against the NDK for four ABIs under `jni/`. It is a separate artifact rather
than a variant of the jar because the two share no native code, and AGP would
otherwise package the desktop `.so`, `.dylib` and `.dll` into every APK as dead
java resources.

The aar is assembled by hand rather than by the Android Gradle Plugin, which
keeps this build free of the Android SDK. Nothing about that is correct by
construction, so `android-verify/` is a real consumer project that resolves the
aar by coordinate and runs the FFI on an emulator against the shared
conformance vectors. It runs in ci on every change. A library whose native
loading is only inspected, never executed, is a library nobody has tested.

```bash
./scripts/build-android-core.sh            # four ABIs, needs the NDK
gradle :lnurlcash-kotlin-android:publishToMavenLocal
(cd android-verify && ./gradlew connectedDebugAndroidTest)   # needs a device
```

## Kotlin Multiplatform

A UniFFI aar does not fit KMP cleanly. If pure-KMP support is ever needed, the
conformance vectors are what would make a hand-written implementation
acceptable, and the public API here would not change.

## Reference implementations

Both by dni, both MIT: [lnurl-mint](https://github.com/dni/lnurl-mint) and
[lnurl-wallet](https://github.com/dni/lnurl-wallet).

The wider ecosystem — wallets, mints, hardware and the sibling ports — is
indexed in [awesome-lnurlcash](https://github.com/lnurlcash/awesome-lnurlcash).

## License

MIT.
