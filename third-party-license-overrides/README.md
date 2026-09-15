# Licence overrides

The notice generator normally copies licence material directly from each
resolved crate archive. These two pinned releases declare a licence but omit
the workspace-level file from the published crate, so the missing text is
preserved here and used only for the named version.

- `bech32-0.9.1.txt` is the MIT notice at the top of `src/lib.rs` in
  `bech32 0.9.1`, whose Cargo VCS commit is
  `d12513f1e3768a7f2a2e1b4d650139eb92a4ae9b`.
- `uniffi-rs-0.28.3-MPL-2.0.txt` is the root `LICENSE` from UniFFI's Cargo VCS
  commit `0d38fc51ec057c271233b70db51b13ef403e360d`. It applies to the UniFFI
  `0.28.3` workspace crates named by the generator.

Do not add a broad fallback. A newly missing licence must stop the release
until its exact upstream notice and version have been reviewed.
