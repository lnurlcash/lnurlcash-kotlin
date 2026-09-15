#!/usr/bin/env bash
# Generates the notices shipped beside the compiled Rust core.
#
# The dependency set comes from the exact lnurlcash-core commit in core.sha.
# Cargo metadata supplies the resolved graph and each downloaded crate supplies
# its own licence files. Two pinned upstream releases omitted those files from
# their crate archives, so their exact notices are committed as narrow
# overrides under third-party-license-overrides/.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CORE="${LNURLCASH_CORE:-$ROOT/../lnurlcash-core}"
EXPECTED_SHA="$(tr -d '[:space:]' < "$ROOT/core.sha")"
OUTPUT="$ROOT/THIRD_PARTY_NOTICES.txt"
OVERRIDES="$ROOT/third-party-license-overrides"

command -v cargo >/dev/null || { echo "cargo is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
[ -d "$CORE/.git" ] || git -C "$CORE" rev-parse --git-dir >/dev/null 2>&1 || {
  echo "lnurlcash-core not found at $CORE - set LNURLCASH_CORE" >&2
  exit 1
}

ACTUAL_SHA="$(git -C "$CORE" rev-parse HEAD)"
[ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] || {
  echo "lnurlcash-core is at $ACTUAL_SHA, but core.sha pins $EXPECTED_SHA" >&2
  exit 1
}
git -C "$CORE" diff --quiet -- Cargo.toml Cargo.lock || {
  echo "lnurlcash-core Cargo.toml or Cargo.lock has uncommitted changes" >&2
  exit 1
}

TMP="$(mktemp -d "${TMPDIR:-/tmp}/lnurlcash-notices.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
METADATA="$TMP/metadata.json"
TREE_KEYS="$TMP/tree-keys.tsv"
TREE_KEYS_JSON="$TMP/tree-keys.json"
RECORDS="$TMP/packages.txt"
GENERATED="$TMP/THIRD_PARTY_NOTICES.txt"
NORMALISED="$TMP/THIRD_PARTY_NOTICES.normalised.txt"
SEP=$'\x1f'

cargo metadata --locked --format-version 1 --features ffi \
  --manifest-path "$CORE/Cargo.toml" > "$METADATA"

# `cargo metadata` exposes disabled optional dependencies in its node data, so
# walking that data directly would over-report tools that never enter a native
# build. `cargo tree` applies feature activation first. Normal edges exclude
# dev-only tests and build-script implementation dependencies. `--target all`
# deliberately takes a conservative superset of the six desktop targets and
# four Android ABIs, so adding a target cannot silently omit its notices.
cargo tree --locked --features ffi --edges normal --target all --prefix none \
  --format '{p}' --manifest-path "$CORE/Cargo.toml" \
  | awk '{ version=$2; sub(/^v/, "", version); print $1 "\t" version }' \
  | LC_ALL=C sort -u > "$TREE_KEYS"
jq -Rn '[inputs | split("\t") | {name: .[0], version: .[1]}]' \
  < "$TREE_KEYS" > "$TREE_KEYS_JSON"

jq -r --slurpfile wanted "$TREE_KEYS_JSON" --arg sep "$SEP" '
  .packages[] as $package
  | select(any($wanted[0][]; $package.name == .name and $package.version == .version))
  | $package
  | [
      .name,
      .version,
      (.license // ""),
      ((.authors // []) | join("; ")),
      (.repository // .homepage // ""),
      .manifest_path,
      (.license_file // "")
    ]
  | join($sep)
' "$METADATA" | LC_ALL=C sort > "$RECORDS"

expected_count="$(wc -l < "$TREE_KEYS" | tr -d ' ')"
actual_count="$(wc -l < "$RECORDS" | tr -d ' ')"
[ "$actual_count" = "$expected_count" ] || {
  echo "cargo tree names $expected_count packages but metadata matched $actual_count" >&2
  echo "a duplicate name/version from different sources needs an explicit generator fix" >&2
  exit 1
}

{
  echo "THIRD-PARTY SOFTWARE NOTICES"
  echo
  echo "This distribution bundles lnurlcash-core as native libraries. These notices"
  echo "conservatively cover its feature-enabled normal dependency graph across all"
  echo "Cargo targets. The graph is generated from Cargo.lock at the exact core"
  echo "commit pinned by this release:"
  echo
  echo "  $EXPECTED_SHA"
  echo
  echo "Each component remains under its declared licence. Project and source URLs"
  echo "identify where the corresponding source code can be obtained. Licence files"
  echo "are copied from the resolved crate archives unless an explicitly named"
  echo "pinned override is noted. This file does not change any licence terms."
  echo
} > "$GENERATED"

while IFS="$SEP" read -r name version license authors project manifest license_file; do
  [ -n "$license" ] || {
    echo "$name $version has no declared licence" >&2
    exit 1
  }

  crate_dir="${manifest%/Cargo.toml}"
  materials="$TMP/materials"
  : > "$materials"
  find "$crate_dir" -maxdepth 1 -type f \
    \( -iname 'LICENSE*' -o -iname 'COPYING*' -o -iname 'NOTICE*' -o -iname 'UNLICENSE*' \) \
    -print >> "$materials"
  if [ -n "$license_file" ] && [ -f "$license_file" ]; then
    echo "$license_file" >> "$materials"
  fi
  LC_ALL=C sort -u -o "$materials" "$materials"

  override=""
  if [ ! -s "$materials" ]; then
    case "$name@$version" in
      bech32@0.9.1)
        override="$OVERRIDES/bech32-0.9.1.txt"
        ;;
      uniffi@0.28.3|uniffi_*@0.28.3)
        override="$OVERRIDES/uniffi-rs-0.28.3-MPL-2.0.txt"
        ;;
      *)
        echo "$name $version declares $license but ships no licence material" >&2
        echo "add a reviewed, version-specific override before releasing" >&2
        exit 1
        ;;
    esac
    [ -f "$override" ] || { echo "missing licence override $override" >&2; exit 1; }
  fi

  {
    echo "================================================================================"
    echo "$name $version"
    echo "Declared licence: $license"
    [ -z "$authors" ] || echo "Authors: $authors"
    if [ -n "$project" ]; then
      echo "Project/source: $project"
    elif [ "$name" != "lnurlcash-core" ]; then
      echo "Project/source: https://crates.io/crates/$name/$version"
    fi
    echo
    if [ -n "$override" ]; then
      echo "Licence material: pinned override $(basename "$override")"
      echo "--------------------------------------------------------------------------------"
      sed 's/\r$//' "$override"
      echo
    else
      while IFS= read -r material; do
        echo "Licence material: $(basename "$material")"
        echo "--------------------------------------------------------------------------------"
        sed 's/\r$//' "$material"
        echo
      done < "$materials"
    fi
  } >> "$GENERATED"
done < "$RECORDS"

# Upstream legal text occasionally contains trailing spaces. They carry no
# meaning, make a generated repository file fail the whitespace gate, and can
# vary after line-ending conversions. Preserve every line of text while
# normalising that insignificant formatting and the final newline.
awk '
  {
    sub(/[[:space:]]+$/, "")
    lines[NR] = $0
    if ($0 != "") last = NR
  }
  END { for (line = 1; line <= last; line++) print lines[line] }
' "$GENERATED" > "$NORMALISED"

mv "$NORMALISED" "$OUTPUT"
echo "generated $OUTPUT ($(wc -l < "$OUTPUT" | tr -d ' ') lines)"
