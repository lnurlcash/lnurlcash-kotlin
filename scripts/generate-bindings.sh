#!/usr/bin/env bash
# Regenerates the UniFFI bindings from lnurlcash-core.
#
# The generated file is checked in, so this repo builds without a Rust
# toolchain - but it is generated, never edited. CI regenerates and diffs, so a
# hand edit fails the build rather than quietly becoming the truth.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# resolved to absolute paths up front: both are used from inside subshells that
# have already changed directory, where a relative path means something else
CORE="${LNURLCASH_CORE:-$HERE/../../lnurlcash-core}"
OUT="$HERE/../bindings/src/main/kotlin"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

if [ ! -d "$CORE" ]; then
  echo "lnurlcash-core not found at $CORE - set LNURLCASH_CORE" >&2
  exit 1
fi

case "$(uname -s)" in
  Darwin) LIB=liblnurlcash_core.dylib ;;
  *)      LIB=liblnurlcash_core.so ;;
esac

(cd "$CORE" && cargo build --release --features ffi)
(cd "$CORE" && cargo run --quiet --features bindgen --bin uniffi-bindgen -- \
  generate --library "target/release/$LIB" --language kotlin --out-dir "$OUT")

echo "bindings regenerated into $OUT/uniffi/"
