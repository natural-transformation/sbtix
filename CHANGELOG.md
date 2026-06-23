# Changelog

## 1.2.7

### Fixed

- Deduplicate content-equivalent Maven artifacts across resolver namespaces so stale duplicate mirror URLs do not break offline Nix builds when an equivalent locked public artifact is available.

## 1.2.6

### Fixed

- Preserve original relocated POM metadata needed for offline sbt resolution after Coursier resolves the replacement artifacts.

## 1.2.5

### Fixed

- Keep the sbt 2.x wrapper compatible with generated Bloop plugin files that reference versions without sbt 2 artifacts.

## 1.2.4

### Fixed

- Keep the sbt 2.x wrapper compatible with generated Metals plugin files that reference IDE-only plugins without sbt 2 artifacts.

## 1.2.3

### Fixed

- Keep the sbt 2.x wrapper compatible with generated Metals/Bloop plugin files in recursive meta-builds.
- Resolve the sbt 2.x plugin's uPickle dependency on the Scala 2.13 axis to avoid mixed Scala 3 / Scala 2.13 plugin dependency conflicts.

## 1.2.2

### Fixed

- Load the sbt 2.x plugin artifact automatically from the bundled CLI wrapper while preserving the existing sbt 1.x wrapper path.
- Compare loaded sbtix plugin jars by canonical file path so generated compositions work when the wrapper exposes the in-store plugin through Ivy local.

## 1.2.1

### Fixed

- Lock sbt 2 launcher boot artifacts from the sbt root POM classpath so sbt 2 projects can start in sandboxed/offline Nix builds.
- Publish both sbt 1.x and sbt 2.x sbtix plugin artifacts in the bundled CLI wrapper.

## 1.2.0

### Added

- Cross-build the sbtix plugin for sbt 1.x / Scala 2.12 and sbt 2.x / Scala 3.
- Add sbt-version-specific compatibility shims for APIs that differ between sbt 1 and sbt 2.

### Changed

- Keep sbt plugin resolver, credential, and POM traversal handling compatible with both sbt lines.
- Preserve the existing sbt 1.x artifact layout and generated bootstrap behavior for current users.

## 1.1.1

### Fixed

- Lock POM metadata for direct project dependencies that are evicted from the resolved classpath, so offline Nix builds still have the original root dependency metadata sbt resolves before eviction.
- Use Scala cross-versioned module names when constructing direct module POM paths.
- Add scripted regression coverage for evicted direct dependency POM locking and run it in CI.

## 1.1.0

### Changed

- Update sbt support to 1.12.11 and refresh the offline plugin bootstrap locks for the new sbt/Scala 2.12 baseline.
- Update Scala 3 scripted coverage to the latest 3.3.x LTS release.
- Improve deterministic POM metadata locking for project and sbt plugin repositories, including evicted module metadata needed by offline builds.
- Document that opportunistic missing POM metadata fetching is limited to Maven Central; private and Ivy metadata should be resolved by Coursier or pinned in `manual-repo.nix`.

## 1.0.3

### Fixed

- Lock already-resolved project and plugin update reports for offline builds, including classifier artifacts, cached module POMs, parent POMs, import-scoped BOMs, and authenticated private artifacts.
- Synthesize local Maven metadata from concrete locked versions instead of pinning mutable upstream `maven-metadata.xml` files, so sandboxed builds can replay version-range resolution without depending on changing repository metadata.

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
