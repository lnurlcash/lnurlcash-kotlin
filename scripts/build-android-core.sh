#!/usr/bin/env bash
# Builds the Rust core for Android's ABIs, using the NDK's clang directly.
#
# Deliberately not cargo-ndk. This is a dozen lines of environment, and the
# thing it sets up - which compiler builds the C in secp256k1-sys, and which
# API level it targets - is exactly the thing worth being able to read.
#
# ANDROID_NDK_HOME, or an `ndk/<version>` under ANDROID_HOME, must be present.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CORE="${LNURLCASH_CORE:-$ROOT/../lnurlcash-core}"

# 21 is Android 5.0. Below that the NDK drops APIs the Rust standard library
# wants, and above it buys nothing this crate needs.
API="${ANDROID_API_LEVEL:-21}"

# ANDROID_NDK_LATEST_HOME is what the GitHub runner images set, so CI needs no
# action to install one.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  NDK="$(find "$SDK/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "no Android NDK found - set ANDROID_NDK_HOME" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  Linux)  HOST_TAG=linux-x86_64 ;;
  *) echo "unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$BIN" ] || { echo "NDK toolchain not at $BIN" >&2; exit 1; }

# triple : ABI directory : the NDK's compiler prefix for that triple. armv7 is
# the odd one: its Rust triple says armv7-...-androideabi, the NDK calls the
# compiler armv7a-linux-androideabi, and the ABI directory is armeabi-v7a.
TARGETS="
aarch64-linux-android:arm64-v8a:aarch64-linux-android
armv7-linux-androideabi:armeabi-v7a:armv7a-linux-androideabi
x86_64-linux-android:x86_64:x86_64-linux-android
i686-linux-android:x86:i686-linux-android
"

for spec in $TARGETS; do
  TRIPLE="${spec%%:*}"; rest="${spec#*:}"
  ABI="${rest%%:*}"; PREFIX="${rest##*:}"
  CLANG="$BIN/${PREFIX}${API}-clang"
  [ -x "$CLANG" ] || { echo "no compiler at $CLANG" >&2; exit 1; }

  rustup target add "$TRIPLE" >/dev/null 2>&1 || true

  # Cargo wants the triple upper-cased with dashes as underscores; the cc
  # crate, which compiles secp256k1's C, wants it verbatim. Both have to be
  # set or the C and the Rust get built by different compilers.
  ENVNAME="$(echo "$TRIPLE" | tr 'a-z-' 'A-Z_')"
  env \
    "CARGO_TARGET_${ENVNAME}_LINKER=$CLANG" \
    "CC_${TRIPLE}=$CLANG" \
    "AR_${TRIPLE}=$BIN/llvm-ar" \
    cargo build --manifest-path "$CORE/Cargo.toml" --release --features ffi --target "$TRIPLE"

  mkdir -p "$ROOT/android-natives/$ABI"
  cp "$CORE/target/$TRIPLE/release/liblnurlcash_core.so" "$ROOT/android-natives/$ABI/liblnurlcash_core.so"
  echo "built $ABI"
done

echo
echo "Android natives under $ROOT/android-natives:"
find "$ROOT/android-natives" -type f -exec ls -lh {} + | awk '{print "  " $9, $5}'
