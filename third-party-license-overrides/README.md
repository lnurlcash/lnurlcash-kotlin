# Licence overrides

The notice generator normally copies licence material directly from each
resolved crate archive. These pinned releases declare a licence but omit
the workspace-level file from the published crate, so the missing text is
preserved here and used only for the named version.

- `bech32-0.9.1.txt` is the MIT notice at the top of `src/lib.rs` in
  `bech32 0.9.1`, whose Cargo VCS commit is
  `d12513f1e3768a7f2a2e1b4d650139eb92a4ae9b`.
- `uniffi-rs-0.28.3-MPL-2.0.txt` is the root `LICENSE` from UniFFI's Cargo VCS
  commit `0d38fc51ec057c271233b70db51b13ef403e360d`. It applies to the UniFFI
  `0.28.3` workspace crates named by the generator.
- `r-efi-6.0.0-AUTHORS.txt` is the `AUTHORS` file shipped in the `r-efi 6.0.0`
  crate (Cargo VCS commit `7e1b0322d31d625f81a5656096330934f9cd835d`), which
  holds the MIT, Apache-2.0 and LGPL-2.1+ grant texts and the copyright
  holders (the individual contributor list that follows them is omitted); the crate declares `MIT OR Apache-2.0 OR LGPL-2.1-or-later` and
  carries no separate licence file. It arrives through `getrandom 0.4`.

Do not add a broad fallback. A newly missing licence must stop the release
until its exact upstream notice and version have been reviewed.
