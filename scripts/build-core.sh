#!/usr/bin/env bash
# Builds the Rust core the tests load through JNA.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="${LNURLCASH_CORE:-$HERE/../../lnurlcash-core}"
(cd "$CORE" && cargo build --release --features ffi)
echo "core built into $CORE/target/release"
