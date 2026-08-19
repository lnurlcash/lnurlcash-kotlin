# Threat model

What this library defends against, what it cannot, and what it hands to you.

## What it is

A Kotlin and JVM client for LNURLcash bearer notes. The protocol logic lives in
the Rust core, reached through UniFFI; this layer owns the HTTP, the
coroutines, and the types. It holds no state between calls.

## Assets

**Note secrets (`k1`).** Bearer instruments. Whoever holds one can spend it,
with no further authentication, from anywhere. Compromise is theft, and it
is silent and irreversible: the money is gone before the previous holder has
any way to notice.

**Replacement secrets awaiting confirmation.** After a mutation whose outcome
is unknown, the secrets generated in-process may be the only copies of notes
a service has already minted. Losing them destroys the money exactly as
thoroughly as leaking them gives it away.

**Mint pubkeys.** Not secret, but a wrong one makes offline verification
meaningless.

## Trust boundaries

| Party | Trusted for |
| --- | --- |
| the SERVICE (mint) | custody of the sats, and honest accounting. Nothing else. |
| the caller's storage | confidentiality and durability of secrets. This library provides neither. |
| the caller's RNG | unpredictability of replacement secrets. Substitutable, and load-bearing. |
| the network | nothing. |

A mint is trusted with custody by construction — it holds the funds. It is
*not* trusted to describe them accurately, which is why value comes from
`maxWithdrawable` rather than from a note URL's own `amount`, and why a
signed note can be checked against a key the mint published earlier.

## What this library defends against

**A service that keeps a copy of your note.** Rotate, split and merge disclose
only `sha256(secret)`. The service registers the note under that hash and
never sees the secret. This is the difference between a bearer note and a
receipt, and it is why a service-generated replacement is refused even when
offered (`serverGeneratedSecrets` in the mock mint exercises exactly this).

**A mutation whose outcome is unknown.** Timeouts, dropped connections,
unreadable bodies and unconfirmed 200s are all raised as
`AmbiguousMutationError` carrying the fresh secrets, never as failure.
Requests that provably never left — offline mode, a refused URL, an
unparseable callback — are raised as `RequestRefusedError` instead, which is
safe to treat as "nothing happened".

**A note that answers its own questions.** Every URL fetched, whether scanned
by a user or supplied by a service in its own response, must be https, or
http to loopback or `.onion`. A `data:` URL carrying withdrawRequest JSON
would otherwise mint a self-contained fake note that verifies against
nothing.

**A service that inflates a note.** With offline verification configured, the
signature commits to the amount. A service reporting more than it signed
fails verification, without the holder contacting anyone.

**A service that swaps your note.** The informational GET checks that the
echoed `k1` is the one queried. A different one means either a non-compliant
service or a note redeemed by somebody else.

**A secret leaking through a query string.** `sig` is stripped before the
informational GET, since the service already knows what it signed.

**A hostile fee advertisement.** Fees of 100% or more are refused at parse
time, and the gross-up search is a binary search rather than a walk — so a
service cannot stall a caller with an extreme fee.

**Integer overflow on realistic amounts.** The proportional fee term is
computed split, because 21M BTC in msat times a high ppm exceeds 64-bit
unsigned. Ports that multiply naively pass every small test and mangle large
ones; the conformance vectors include a case that catches it.

## What it does not defend against

**Storage compromise.** This library never persists anything. If your
storage is readable — an unencrypted database, a synced folder, a debugger,
a crash dump — every note in it is spendable by whoever reads it. Encrypt at
rest, and treat backups as the same exposure.

**A weak RNG.** `LnurlcashClient(secretSource = ...)` is replaceable, which
means it can be replaced badly. A predictable secret is a note anyone can mint themselves. Use the
platform CSPRNG, or a hardware RNG, and nothing else.

**Secrets in logs.** A note URL carries its secret in a query string. A
request logger, an error reporter, a crash handler or an analytics SDK that
records URLs records bearer money. This library never logs; what wraps it
might.

**A malicious or compromised mint.** It holds the funds. It can refuse to
honour a note, vanish, or inflate its liabilities. Offline verification
proves what it *said*, which is useful for exposing it afterwards, and is
not custody.

**The mint-time preimage race.** A freshly minted note's secret is the
invoice preimage, so the service has necessarily seen it, and anyone who saw
the unpaid invoice can poll LUD-21 `verify` for it the moment it settles.
Rotating immediately wins that race; a slow manual flow does not. Do not
publish unpaid mint invoices.

**Traffic analysis.** Every operation reaches the mint directly. The mint
learns your IP, your timing, and which notes move together. Notes are bearer
instruments, not private ones — merging several notes tells the mint they
had one holder. Route over Tor if that matters.

**Anything about the sats themselves.** No custody, no channel management,
no payment routing.

## The JVM's retry hazard

Every LNURLcash mutation is an HTTP GET, and HTTP considers GET idempotent, so
clients retry one when a connection fails mid-flight. An LNURLcash mutation is
not idempotent: the first attempt burns the input.

A retried mutation therefore gets "already spent" for its second attempt, which
classifies as a *definitive rejection*. A caller then discards the fresh secret
that was the only copy of the note the service had just minted.

`java.net.http.HttpClient` does this and cannot be configured out of it. This
library uses OkHttp with `retryOnConnectionFailure(false)` for that reason
alone. **A caller supplying its own client must disable retries**, and a caller
adding a retry interceptor, a resilience library, or a service mesh with
automatic retries must exclude these requests from it.

The same hazard applies to any LNURLcash client on any platform whose HTTP
stack retries idempotent methods.

## Deliberate design choices

**Both signature recovery-id orderings are accepted.** The wire format is
`r || s || recovery_id`; lnurl-mint once emitted the reverse. Trying both is
not a weakening: recovering under the wrong ordering yields an unrelated
pubkey, which cannot match the expected one.

**Errors are typed by whether the request could have been processed**, not by
transport detail. That distinction is the whole safety model, and message
text is not a stable interface — never branch on it.

**No global state.** Offline mode, timeout, HTTP client and RNG are
constructor parameters. A caller that wants certainty nothing reaches the
network sets `offline = true` and gets a refusal, rather than trusting that no
code path happens to make a request.

**Mutations return a value rather than throwing.** `MutationOutcome.Unknown` is
a branch the compiler will not let a caller ignore. A `catch` block treats "the
answer was lost" and "the service said no" identically, and for a mutation
those differ by the entire value of the note.

**The HTTP client does not retry.** See below - this is the sharpest edge on
the JVM.

## Reporting

See [SECURITY.md](SECURITY.md).
