rootProject.name = "lnurlcash-kotlin"

// The generated UniFFI bindings live in their own module. They are machine
// written, so they cannot satisfy the explicit-API rules the hand-written
// wrapper is held to - and keeping them separate makes the boundary between
// "generated, do not edit" and "designed" impossible to miss.
//
// The module is NAMED for the coordinate it publishes under, while its
// directory stays `bindings`. A Gradle project dependency is rendered into the
// POM using the target project's name, so a module called `bindings` would
// publish `io.github.thecryptodonkey:lnurlcash-kotlin` with a dependency on a
// `bindings` artifact that does not exist under that group. Naming the project
// after the artifact removes the chance of that ever drifting apart.
include(":lnurlcash-kotlin-bindings")
project(":lnurlcash-kotlin-bindings").projectDir = file("bindings")

// Android gets its own artifact rather than a variant of the jar, because the
// two share no native code at all: the jar carries desktop .so/.dylib/.dll at
// its root, which AGP would package into every APK as dead java resources, and
// Android needs its ABIs under jni/ instead. JNA itself publishes a jar and an
// aar with overlapping classes for exactly this reason.
include(":lnurlcash-kotlin-android")
project(":lnurlcash-kotlin-android").projectDir = file("android")
