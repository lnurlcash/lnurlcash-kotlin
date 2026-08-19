# Security policy

## Supported versions

`0.x` tracks a draft spec ([LUD-25](https://github.com/lnurl/luds/pull/301)).
Only the latest `0.x` release is supported. Pin an exact version.

## Reporting a vulnerability

Report privately through GitHub's advisory form:

<https://github.com/TheCryptoDonkey/lnurlcash-kotlin/security/advisories/new>

Please do not open a public issue for anything that could be used to take
somebody's notes.

Include what you can: affected version, a reproduction, and what an attacker
gets out of it. A rough report today beats a polished one next month.

Expect an acknowledgement within a few days. This is maintained by one
person, so timelines are best-effort rather than contractual.

## Scope

**In scope**: anything in this library that could lose or leak a note —
secrets on the wire, a mutation misclassified as definitive, verification that
accepts a signature it should not, URL admission bypasses, a fee or amount
calculation a hostile mint can steer.

**Out of scope**: how a calling application stores secrets, a weak generator
passed as `rng`, the behaviour of any particular mint, and the LUD-25 draft
itself — spec concerns belong on
[the PR](https://github.com/lnurl/luds/pull/301), where they help everyone.

If a finding affects the protocol rather than this implementation, it is worth
telling the reference implementations too:
[lnurl-mint](https://github.com/dni/lnurl-mint) and
[lnurl-wallet](https://github.com/dni/lnurl-wallet).
