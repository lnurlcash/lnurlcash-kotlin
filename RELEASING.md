# Releasing lnurlcash-kotlin

Two coordinates go to Maven Central together:

| Coordinate | What it is |
|---|---|
| `io.github.thecryptodonkey:lnurlcash-kotlin` | the library a JVM project depends on |
| `io.github.thecryptodonkey:lnurlcash-kotlin-bindings` | generated UniFFI bindings, and the native core compiled for six desktop platforms |
| `io.github.thecryptodonkey:lnurlcash-kotlin-android` | an aar: the same Kotlin, plus the core for four Android ABIs under `jni/` |

A JVM project names the first and gets the second transitively; an Android
project names the third and gets nothing else. The android artifact is
self-contained rather than depending on the jar, because the jar's desktop
natives would otherwise be packaged into every APK as dead java resources -
the same reason JNA publishes a jar and an aar with overlapping classes. They are separate because the bindings are machine
written and cannot satisfy the explicit-API rules the hand-written wrapper is
held to, and folding them into one jar would mean stripping a project
dependency out of both the POM and the Gradle module metadata by hand. Two
honest coordinates beat one jar assembled by a trick.

## What makes this release different from an npm one

The jar carries compiled machine code, so three things have to line up that a
pure-source release never has to think about.

**Every platform, or none.** JNA resolves an unfound library from the classpath
at `/<Platform.RESOURCE_PREFIX>/<mapped name>`. Ship five of the six prefixes
and consumers on the sixth get an `UnsatisfiedLinkError` at their first call —
after resolving a dependency that looked fine. Two checks are hard
preconditions of `publish` for exactly that reason: `verifyNativeLibraries`
looks at the `natives/` directory, and `verifyPackagedNatives` opens the jar
that is about to be published, because a file can sit on disk and still not be
packaged. The second also rejects anything under a kilobyte, which is what a
placeholder left over from a local rehearsal looks like.

**The natives and the bindings must come from one core commit.** `core.sha`
pins `lnurlcash-core` to a full 40-character commit. Every runner in the matrix
builds that commit, and before anything is assembled the release regenerates
the bindings against it and fails on a single byte of difference. A binding
that disagrees with the library it calls is undefined behaviour on a money
path, and nothing downstream would notice.

**Central is permanent.** A version cannot be replaced or withdrawn, only
superseded. So the default `publishingType` is `USER_MANAGED`: the deployment
is uploaded, validated, and then *held* until a human presses publish in the
Portal.

## One-off setup

### 1. Claim the namespace

`io.github.thecryptodonkey` is verified by proving control of the GitHub
account. On [central.sonatype.com](https://central.sonatype.com), add the
namespace; it hands back a temporary repository name. Create a public repo with
exactly that name under `TheCryptoDonkey`, press verify, delete the repo. The
namespace is then permanently yours.

### 2. A Portal token

Account, Generate User Token. It gives a username and password pair that is
**not** the account password. These become `CENTRAL_USERNAME` and
`CENTRAL_PASSWORD`.

### 3. A signing key

Central rejects an unsigned deployment, and the signature is the only thing
tying a jar on Central to this repo.

```bash
gpg --quick-generate-key "TheCryptoDonkey <TheCryptoDonkey@users.noreply.github.com>" rsa4096 sign 2y
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # Central checks this
gpg --armor --export-secret-keys <KEY_ID>                   # the secret below
```

Publish the *public* key to a keyserver, and keep the armoured *private* key
out of everything except the repository secret.

### 4. Secrets, on a protected environment

The publish job runs in the `maven-central` environment. Create it under
Settings, Environments, add a required reviewer if you want a second pair of
eyes on every release, and set four secrets there:

| Secret | Value |
|---|---|
| `MAVEN_GPG_PRIVATE_KEY` | the full armoured private key, `-----BEGIN` line included |
| `MAVEN_GPG_PASSPHRASE` | its passphrase |
| `CENTRAL_USERNAME` | Portal token username |
| `CENTRAL_PASSWORD` | Portal token password |

## Cutting a release

1. Bump `version` in `build.gradle.kts`.
2. If the Rust core moved, bump `core.sha` to the new commit and regenerate:

   ```bash
   ./scripts/build-core.sh
   ./scripts/generate-bindings.sh
   ```

   Commit the regenerated `bindings/` in the same change as the pin. They are
   one fact and reviewing them apart hides the interesting half.
3. Add the `## x.y.z` section to `CHANGELOG.md`.
4. Push to `main`, wait for ci to go green.
5. Cut the release:

   ```bash
   gh release create v0.1.0 --title v0.1.0 --notes-from-tag
   ```

   `release: published` fires `release.yml`. Six runners build the pinned core,
   the assemble job proves the bindings match it, Gradle signs both modules into
   one deployment tree, and `publish-central.sh` uploads it as a single bundle.
6. The run stops at `VALIDATED`. Release it at
   [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).
   It reaches `search.maven.org` within a few hours.

To publish without the hold, run the workflow by hand from Actions and choose
`AUTOMATIC`. That is irreversible.

## Checking it locally first

You cannot rehearse the whole matrix on one machine, but you can rehearse the
part that usually breaks — whether the jar can find its own native core:

```bash
./scripts/package-natives.sh   # builds this platform, into natives/<prefix>/
gradle build
```

Then put `build/libs/lnurlcash-kotlin-*.jar`,
`bindings/build/libs/lnurlcash-kotlin-bindings-*.jar`, `jna` and
`kotlin-stdlib` on a classpath somewhere else entirely, with
`-Djna.library.path` pointed at nothing, and call `isAllowedServiceUrl`. It
crosses the FFI boundary, so reaching an answer at all proves the library
loaded from inside the jar rather than from a sibling checkout.

`gradle publishAllPublicationsToStagingRepository` assembles the deployment
under `build/staging-deploy` without uploading anything, which is the fastest
way to look at what the POMs actually say.

## The Android artifact is verified by running it

The aar is assembled by a Zip task, not by the Android Gradle Plugin, which is
what keeps this build free of the Android SDK. Nothing about that is correct by
construction, so `android-verify/` is a real consumer project: it resolves the
aar by coordinate through AGP, and its instrumented tests call the FFI on an
emulator against the shared conformance vectors. ci runs it on every change,
and also asserts that all four ABIs reached the APK.

That project pins its own Gradle through a wrapper. AGP 8 relies on a Gradle
internal API removed in 9.6, and AGP 9's built-in Kotlin is 2.2, so neither the
library's Gradle nor its Kotlin can be reused there.

## Rehearsing it, without publishing anything

The six cross-platform builds should not be attempted for the first time during
a release nobody can take back. Actions, release, Run workflow, tick **dry
run**: every native is built, the bindings are checked against the pinned core,
both modules are assembled, and the upload is skipped. The deployment is left
as a workflow artifact to look at.

A dry run needs none of the secrets, so it works before any of the setup above
is done. Without a signing key the deployment comes out unsigned, which is the
one thing it does not prove.

## Known rough edge

The `lnurlcash-kotlin-bindings` javadoc jar is a single page pointing at the
real documentation. Running Dokka over machine-written bindings would produce a
hundred pages describing `FfiConverterUInt64`, which helps nobody; Central only
requires the artifact to exist. `lnurlcash-kotlin` itself ships real Dokka
javadoc.

Kotlin Multiplatform is not covered, and a UniFFI aar does not fit it cleanly.
The conformance vectors are what would make a hand-written implementation
acceptable if it is ever wanted; the public API would not change.
