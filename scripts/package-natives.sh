#!/usr/bin/env bash
# Builds the Rust core for THIS machine and installs it where JNA will find it
# on the classpath, so the packaging path itself can be exercised locally
# rather than only in the release.
#
# The release does not run this: its matrix builds one target per runner and
# drops each result straight into natives/. This is the single-platform
# version, for checking that a jar built here loads its own native core.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CORE="${LNURLCASH_CORE:-$ROOT/../lnurlcash-core}"

# The directory name is JNA's Platform.RESOURCE_PREFIX, not a name we get to
# pick. `uname -m` reports the kernel's idea of the architecture, which is what
# the JVM will agree with.
case "$(uname -s)" in
  Darwin)
    case "$(uname -m)" in
      arm64) PREFIX=darwin-aarch64 ;;
      x86_64) PREFIX=darwin-x86-64 ;;
      *) echo "unsupported macOS architecture: $(uname -m)" >&2; exit 1 ;;
    esac
    LIBRARY=liblnurlcash_core.dylib
    ;;
  Linux)
    case "$(uname -m)" in
      x86_64) PREFIX=linux-x86-64 ;;
      aarch64) PREFIX=linux-aarch64 ;;
      *) echo "unsupported Linux architecture: $(uname -m)" >&2; exit 1 ;;
    esac
    LIBRARY=liblnurlcash_core.so
    ;;
  *)
    echo "unsupported platform: $(uname -s). The release matrix covers Windows." >&2
    exit 1
    ;;
esac

(cd "$CORE" && cargo build --release --features ffi)

mkdir -p "$ROOT/natives/$PREFIX"
cp "$CORE/target/release/$LIBRARY" "$ROOT/natives/$PREFIX/$LIBRARY"
echo "installed natives/$PREFIX/$LIBRARY"
echo
echo "Only this platform is present, so a jar built now loads on this machine"
echo "and nowhere else. gradle publish refuses a set with a hole in it."
