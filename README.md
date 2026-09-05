# lnurlcash-kotlin

LNURLcash ([LUD-25 draft](https://github.com/lnurl/luds/pull/301)) bearer notes
for Kotlin and the JVM, over the audited
[Rust core](https://github.com/TheCryptoDonkey/lnurlcash-core).

```kotlin
dependencies {
    implementation("io.github.thecryptodonkey:lnurlcash-kotlin:0.1.0")
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
[the core](https://github.com/TheCryptoDonkey/lnurlcash-core) yourself and
point `-Djna.library.path` at it. The Kotlin side is unchanged either way.

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

## Offline verification is mandatory

A service MUST publish `mintPubkey` and MUST sign every note a rotate, split or
merge mints. `fetchNoteInfo` throws for a `withdrawRequest` publishing no valid
one, and a mutation the service confirms but does not sign comes back as
`MutationOutcome.Unverifiable` — its own case, because the mutation **landed**:
the note is real, and `newSecrets` is the only key to it. Persist them before
anything else.

`LnurlcashClient(requireSignatures = false)` opts out for a service that
predates the requirement.

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
    // the mutation landed, and the mint did not sign what it minted
    is MutationOutcome.Unverifiable -> save(outcome.newSecrets)
}
```

That `when` is the reason this wrapper exists. Mutating operations return
[`MutationOutcome`](src/main/kotlin/io/github/thecryptodonkey/lnurlcash/Types.kt)
rather than throwing, because the dangerous case is not an error — it is an
*unknown*. A `try`/`catch` invites treating "the answer was lost" as "it did
not happen", and for a rotate that already burned the input, that reasoning
destroys money. Making `Unknown` a branch the compiler will not let you ignore
is the whole point.

`getOrThrow()` exists for when you genuinely do not care, and says so.

## What is in Rust and what is in Kotlin

The money-critical logic — request building, response classification, signature
verification, fee arithmetic — lives in
[lnurlcash-core](https://github.com/TheCryptoDonkey/lnurlcash-core) and is
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
[conformance repo](https://github.com/TheCryptoDonkey/lnurlcash-conformance)
alongside this one, or `LNURLCASH_CONFORMANCE` pointing at it — and that
checkout needs its own `npm ci`, because the adversarial mock mint is a Node
process with dependencies of its own:

```bash
git clone https://github.com/TheCryptoDonkey/lnurlcash-conformance ../lnurlcash-conformance
(cd ../lnurlcash-conformance && npm ci)
```

They run the same vectors as the TypeScript, Python, Rust and Go
implementations, plus the adversarial mock mint.

Releases are their own thing; see [RELEASING.md](RELEASING.md).

## Android

The Kotlin in the JVM artifact works on Android as-is, but the natives in it do
not: Android needs its own ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) built
against the NDK and packaged into an AAR, which is a different artifact from
the jar published here. `cargo-ndk` is the usual route. That packaging is
**not yet part of this repo** — it needs an Android SDK to verify, and shipping
an untested build script for the layer that loads native code seemed worse than
saying so plainly.

## Kotlin Multiplatform

A UniFFI AAR does not fit KMP cleanly. If pure-KMP support is ever needed, the
conformance vectors are what would make a hand-written implementation
acceptable, and the public API here would not change.

## Reference implementations

Both by dni, both MIT: [lnurl-mint](https://github.com/dni/lnurl-mint) and
[lnurl-wallet](https://github.com/dni/lnurl-wallet).

The wider ecosystem — wallets, mints, hardware and the sibling ports — is
indexed in [awesome-lnurlcash](https://github.com/TheCryptoDonkey/awesome-lnurlcash).

## License

MIT.
