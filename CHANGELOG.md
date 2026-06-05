# Changelog

## 1.0.2

### Fixed

- Keep declared sbt plugin artifacts locked when `sbt-scalafmt` is also present, so mixed plugin builds keep non-scalafmt plugin artifacts available offline.
- Deduplicate generated Nix artifact keys when plugin resolution reports URL aliases for the same offline path, preferring artifacts whose URL filename matches the generated lock path.

### Added

- Add a flake-native example application and reusable flake template for using sbtix directly from a flake input without generated composition files.

## 1.0.1

### Fixed

- Do not pin mutable Maven `maven-metadata.xml` files or sidecars in generated `repo.nix` locks when Coursier fetches them while resolving version ranges or repository metadata. This keeps generated Nix repositories reproducible after regenerating locks.

## 1.0.0

Sbtix 1.0.0 is the stable baseline for generating locked Nix repositories for sbt builds and building those projects offline in the Nix sandbox.

### Highlights

- Generate `sbtix-generated.nix` compositions that keep downstream flake evaluation pure when sbtix is used from a pinned flake input.
- Build and test the project on GitHub Actions for both x86_64 Linux and ARM Linux.
- Improve `buildSbtProgram` diagnostics when a project is actually a library or does not provide a `stage` task.
- Skip javadoc classifier artifacts by default while keeping `sbtixArtifactClassifiers` available for opt-in classifier locking.
- Support offline sbt-scalafmt builds by locking the configured formatter runtime and relevant plugin artifacts.
- Lock Scala 2.13 and Scala 3 sbt compiler bridge artifacts for sandboxed builds.
- Keep generated `default.nix` handling non-destructive: existing files are left untouched.

### Notes

- `sbtix-gen-all` remains for compatibility and runs `genNix`; `sbtix-gen-all2` is the recommended full regeneration command because it also runs `genComposition`.
- Projects using sbt plugins that download tooling at runtime may still need to disable those tasks under `SBTIX_NIX_BUILD=1` or add rare missing artifacts to `manual-repo.nix`.
