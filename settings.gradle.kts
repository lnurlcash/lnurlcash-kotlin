rootProject.name = "lnurlcash-kotlin"

// The generated UniFFI bindings live in their own module. They are machine
// written, so they cannot satisfy the explicit-API rules the hand-written
// wrapper is held to - and keeping them separate makes the boundary between
// "generated, do not edit" and "designed" impossible to miss.
include("bindings")
