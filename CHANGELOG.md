# Changelog

Semantic versioning. While the LUD-25 draft is unmerged, `0.x` minor bumps may
carry breaking changes; pin an exact version.

## 0.1.0 — unreleased

First release. An idiomatic Kotlin wrapper over
[lnurlcash-core](https://github.com/TheCryptoDonkey/lnurlcash-core), checked
against the shared
[conformance vectors](https://github.com/TheCryptoDonkey/lnurlcash-conformance)
and the adversarial mock mint.

### Design notes

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
