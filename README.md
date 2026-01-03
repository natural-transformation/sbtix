# Sbtix

[Build status (Hercules CI)](https://hercules-ci.com/github/natural-transformation/sbtix)

## What?

Sbtix generates a Nix definition that represents your SBT project's dependencies. It then uses this to build a Maven repo containing the stuff your project needs, and feeds it back to your SBT build.

## Why?

Currently, this should mean that you won't have to redownload the world for each rebuild.

Additionally, this means that Nix can do a better job of enforcing purity where required. Ideally the build script itself should not communicate with the outer world at all, since otherwise Nix does not allow proxy settings to propagate.

* Private (password-protected) Maven stores are supported.
* Ivy repositories and plugins are cached by Sbtix.

## Design Overview

When you run `sbtix genNix` / `genComposition`, the plugin orchestrates three steps:

1. **Collect dependencies:** Sbtix walks your sbt build, resolves everything with Coursier (using your resolvers + credentials), and emits a locked `repo.nix`. Private repositories keep their own namespace (e.g. `nix-private-demo`) so you can see where each artifact originates. For Scala 3 builds, sbtix also locks sbt tooling such as `org.scala-lang:scala3-sbt-bridge` so sbt can compile offline in the Nix sandbox.
2. **Generate the composition:** The plugin now renders **`sbtix-generated.nix`**, the machine-written derivation that wires together `repo.nix`, the plugin bootstrap, and the staging/install logic. If there is no `default.nix` in your project yet, sbtix drops in a tiny example that simply imports `./sbtix-generated.nix`. Your own `default.nix` is never overwritten again.
3. **Consume from Nix:** Import the generated file (`import ./sbtix-generated.nix { inherit pkgs; }`) from your project’s `default.nix`, or call the helpers in `plugin/nix-exprs/sbtix.nix` (`buildSbtProgram`, `buildSbtLibrary`, etc.) if you need a different layout. Either way, the derivation reads the locked `repo.nix` files and the staged application copied in step 2, so builds are reproducible and network-free. The Nix build environment also sets `SBTIX_NIX_BUILD=1` and `-Dsbtix.nixBuild=true` so your build can disable tasks that would otherwise try to download tooling (e.g. scalafmt).

This pipeline keeps your sbt workflow fast (dependency metadata is cached) while ensuring the Nix derivation is always in sync with what the sbt plugin produces.

## Why not? (caveats)

* Pre-1.0 / beta: used in production, but expect occasional breaking changes while the API and Nix/SBT integration stabilizes. Please report any issues!
* Some sbt-internal tooling artifacts are not declared as normal project/library dependencies. sbtix locks the Scala 3 compiler bridge (`org.scala-lang:scala3-sbt-bridge`) automatically, but you may still need to add rare missing artifacts to `manual-repo.nix`.
* **Offline sbt bootstrap is pinned:** the generated `sbtix-plugin-repo.nix` (seeded from sbtix’s bundled template under `plugin/sbtix-plugin-repo.nix`) only includes sbt launcher/bootstrap artifacts for the sbt version shipped with sbtix (currently sbt `1.10.7`, e.g. `org.scala-sbt:main_2.12:1.10.7`). If your project uses a different `sbt.version` in `project/build.properties`, a sandboxed/offline `nix build` can fail unless those sbt boot artifacts are also available offline (typically by aligning `sbt.version`, or by manually pinning the missing sbt artifacts in `manual-repo.nix`).

## How?

To install sbtix either:

```bash
nix shell github:natural-transformation/sbtix
```

Or, from this repository:

```bash
nix build '.#sbtix'
./result/bin/sbtix --help
```

sbtix provides a number of scripts to generate and update Nix expressions to fetch your dependencies and build your sbt project. These scripts work by opening your project in sbt and loading an additional sbtix plugin (via the `sbt.global.base` directory to `$HOME/.sbtix`). After generation, you don't need the sbtix command-line tools to actually build your project from nix.

### Using sbtix from a flake (recommended)

If your project is already a Nix flake, adding sbtix as a flake input is the most
reliable way to keep generation reproducible and flake-pure-safe.

Add an input and expose the sbtix CLI in your dev shell:

```nix
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    # Prefer a tag (or pinned commit) so the sbtix input is stable.
    sbtix.url = "github:natural-transformation/sbtix/v0.5.0";

    # Optional: keep nixpkgs consistent across inputs.
    sbtix.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, sbtix, ... }:
    let
      system = "x86_64-linux"; # or use flake-parts / supported systems
      pkgs = import nixpkgs { inherit system; };
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          sbtix.packages.${system}.sbtix
        ];
      };
    };
}
```

Then regenerate inside the shell:

```bash
nix develop
sbtix-gen-all2
```

When sbtix is run from a flake-provided binary, `sbtix-generated.nix` embeds a
pinned sbtix source reference (`builtins.fetchTree` / `fetchgit`) so downstream
flake evaluation stays pure.

If you want to call sbtix’s Nix helpers directly from your flake, import them
from the sbtix input:

```nix
let
  sbtixLib = pkgs.callPackage "${sbtix}/plugin/nix-exprs/sbtix.nix" {};
in
  sbtixLib.buildSbtProgram { /* ... */ }
```

### Sbtix commands

 * `sbtix` - loads the sbtix global plugin and launches sbt. Sets `sbt.global.base` directory to `$HOME/.sbtix`.
 * `sbtix-gen` - gen build dependencies only, produces `repo.nix`. Alias: `sbtix genNix`.
 * `sbtix-gen-all` - gen build and plugin dependencies, produces `repo.nix` and `project/repo.nix`. Alias: `sbtix genNix "reload plugins" genNix`
 * `sbtix-gen-all2` - gen build, plugin and pluginplugin dependencies, produces `repo.nix`, `project/repo.nix`, and `project/project/repo.nix`. Alias: `sbtix genNix "reload plugins" genNix "reload plugins" genNix`

#### Typical regeneration flow

1. `sbtix-gen-all2` – regenerates every `repo.nix` layer (`repo.nix`, `project/repo.nix`, `project/project/repo.nix`) **and runs `genComposition`** to render `sbtix-generated.nix`. Check these files into VCS so CI and teammates get the same locks.
2. If you did not use `sbtix-gen-all2`, run `sbtix genComposition` to render `sbtix-generated.nix` using the shared template.
   - When sbtix is run from a flake-provided binary (or when `SBTIX_SOURCE_URL`, `SBTIX_SOURCE_REV`, and `SBTIX_SOURCE_NAR_HASH` are set), the generated file embeds a pinned `builtins.fetchTree`/`fetchgit` block that points back to the exact sbtix revision you used for generation. This keeps flake evaluation pure.
   - If those pins are not available, sbtix cannot emit a portable, flake-pure-safe source reference. In that case, regenerate using a flake-provided sbtix (or provide the pins) before running `nix build` in a downstream flake.
3. Commit the refreshed `repo.nix` files (and the generated `.nix` files you intend to keep, e.g. `sbtix-generated.nix`) before running `nix build`.

### Creating a build

* run `sbtix genComposition` (or `sbt genComposition` inside your project) to have sbtix emit `sbtix-generated.nix` for you. The file is rendered from the same template used in our tests, so it already contains the sandbox-friendly `SBT_OPTS`, Ivy generation, and install logic. Check it in as-is and have your `default.nix` (or another entry point) simply import it.
* if you do need to hand-roll the derivation, the snippet below shows how to call `sbtix.buildSbtProgram` directly. (Use `buildSbtLibrary` if you are packaging a library or `buildSbtProject` for a custom `installPhase`.) You can keep your custom logic in `default.nix` while still generating `sbtix-generated.nix` for reference.

If you want a starting point, copy `default.nix.example` from this repository into your project and adapt it. The root-level `default.nix` now just raises an error so it no longer looks like part of the build.

```nix
{ pkgs ? import <nixpkgs> {} }: with pkgs;
let
    sbtixDir = fetchFromGitHub {
        owner = "natural-transformation";
        repo = "sbtix";
        rev = "<<current git rev>>"; # Replace as needed
        sha256 = "<<<corresponding sha256 hash>>>"; # Replace as needed
    };
    sbtix = pkgs.callPackage "${sbtixDir}/plugin/nix-exprs/sbtix.nix" {};
in
    sbtix.buildSbtProgram {
        name = "sbtix-example";
        src = pkgs.lib.cleanSource ./.;
        repo = [ (import ./manual-repo.nix)
                 (import ./repo.nix)
                 (import ./project/repo.nix)
               ];
    }
```

* generate your repo.nix files with one of the commands listed above. `sbtix-gen-all2` is recommended.
* rerun `sbtix genComposition` after changing dependencies to regenerate `sbtix-generated.nix` so it stays in sync with both the template and the sbtix revision you are using.
* check the generated nix files (including `sbtix-generated.nix`) into your source control.
 * finally, run `nix-build` to build!
 * any additional missing dependencies that `nix-build` encounters should be fetched with `nix-prefetch-url` and added to `manual-repo.nix`.

#### Keeping nix files separate from the project sources

In some cases, it can be preferable to keep the nix packaging
definitions separate from the project being packaged. In that
case, move the .nix files elsewhere, and point the `src`
attribute to the location that contains the Scala project.

You're free to choose any filenames and directory layout, as
long as you make sure you update the references from `default.nix`
to the generated repo files. When re-generating the files, you'll
still have to run `sbtix-gen` from the library directory (and
temporarily copy your `sbtix-build-inputs.nix` there if you
use that), then move the generated files to your custom location again.

### Project Types

Libraries and programs need to be "installed" in different ways. Sbtix currently knows how to install "programs" and Maven-style libraries.
The project type is selected by the builder function you use. Use `sbtix.buildSbtProgram` for building programs, and `sbtix.buildSbtLibrary`.

There is also `sbtix.buildSbtProject`, which allows you to define a custom Nix `installPhase`.

#### Programs

Programs must have a `stage` task, and it is assumed that calling this task will put its output in `target/universal/stage`. This folder is then copied
to be the Nix build output.

This is generally fulfilled by SBT-Native-Packager's [sbt-np-jaa](Java Application Archetype).

[sbt-np-jaa]: http://www.scala-sbt.org/sbt-native-packager/archetypes/java_app/index.html

#### Libraries

Libraries are built by running SBT's built-in `publishLocal` task and then copying the resulting Ivy local repo to the Nix output folder.

### Source Dependencies

Sbtix builds can depend on dependencies not found in online repositories
by adding the attr `sbtixBuildInputs` to their call to `buildSbt*`.

These local dependencies should be provided as a derivation that produces
a Maven or Ivy directory layout in its output, such as `buildSbtLibrary`.
You should first package the dependency and then `sbtix-gen` the metadata
for the project using it.

To make sure transitive dependencies and overrides work correctly,
the local dependency must be available both when running 'sbtix-gen'
and when building. You provide it in a `sbtix-build-inputs.nix` file,
which could hold a single dependency:

```nix
{ pkgs ? import <nixpkgs> {} }:

pkgs.callPackage ./path/to/dependency/derivation {}
```

.. or multiple:

```nix
{ pkgs ? import <nixpkgs> {} }:

pkgs.symlinkJoin {
  name = "sbtix-dependencies";

  paths = [
    (pkgs.callPackage ./path/to/dependency/derivation {})
    (pkgs.callPackage ./path/to/other/dependency {})
  ];
}
```

This file is picked up (by name) by `sbtix-gen`, and also should be passed
in as a parameter in the `buildSbtProgram` invocation in your `default.nix`:

```nix
sbtix.buildSbtProgram {
    ...
    sbtixBuildInputs = (pkgs.callPackage ./sbtix-build-inputs.nix {});
    ...
}
```

#### `nix` Extra Attribute

By adding the `nix` [extra attribute](https://www.scala-sbt.org/1.x/docs/Library-Management.html#Extra+Attributes), `sbtix` will ignore the dependency for the purpose of locking.

This used to be the only mechanism for handling local dependencies, but is now a legacy solution and/or escape hatch.

### Authentication

In order to use a private repository, add your credentials to `coursierCredentials`. Note that the key should be the name of the repository, see
`plugin/src/sbt-test/sbtix/private-auth/build.sbt` for an example! Also, you must currently set the credentials for each SBT subproject, `in ThisBuild`
doesn't currently work. This is for consistency with Coursier-SBT.

*Security note*: the repository username and password will be
embedded in the `repo.nix`, and part of your nix store. Having
secrets in your nix store is a security risk, so make sure you
have everything in place to make sure these secrets remain
secret.

### FAQ

Q: Why I am getting errors trying to generate `repo.nix` files when using the PlayFramework?

A: You probably need to add the following resolver to your project for Sbtix to find.

```scala
// if using PlayFramework
resolvers += Resolver.url("sbt-plugins-releases", url("https://dl.bintray.com/playframework/sbt-plugin-releases"))(Resolver.ivyStylePatterns)
```

Q: When I `nix-build` it sbt complains `java.io.IOException: Cannot run program "git": error=2, No such file or directory`

A: You are likely depending on a project via git.  This isn't recommended usage for sbtix since it leads to non-deterministic builds. However you can enable this by making two small changes to sbtix.nix, in order to make git a buildInput.

top of sbtix.nix with git as buildinput
```nix
{ runCommand, fetchurl, lib, stdenv, jdk, sbt, writeText, git }:
```

bottom of sbtix.nix with git as buildinput
```nix
buildInputs = [ jdk sbt git ] ++ buildInputs;
```

Q: How do I disable the generation of a `default.nix`?

A: You have to add `generateComposition := false` to your `build.sbt`.

Q: How do I use a different type of SBT build in `default.nix`

A: You can change the value of `compositionType` in your `build.sbt`. Allowed values are `program` and `library`. In the end the `sbtix.buildSbt{compositionType}` API in the nix expressions will be used.

## Thanks

- [Natalie Klestrup Röijezon](https://gitlab.com/nightkr) - For creating Sbtix
- [Eelco Dolstra](https://github.com/edolstra) - For getting this whole Nix thing started
- [Charles O'Farrel](https://github.com/charleso) - For writing [sbt2nix](https://github.com/charleso/sbt2nix)
- [Chris Van Vranken](https://github.com/cessationoftime) - For sorting out a lot of dependency-fetching bugs, and adding SBT plugin support
- [Maximilian Bosch](https://github.com/Ma27) - For fixing the UX of this thing
