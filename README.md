# Sbtix

[![CI](https://github.com/natural-transformation/sbtix/actions/workflows/ci.yml/badge.svg)](https://github.com/natural-transformation/sbtix/actions/workflows/ci.yml)

## What?

Sbtix generates a Nix definition that represents your SBT project's dependencies. It then uses this to build a Maven repo containing the stuff your project needs, and feeds it back to your SBT build.

## Supported versions

sbtix 2 works with **sbt 2.x** builds. It is built and tested with sbt 2.0.12.
For an sbt 1.x build, stay on sbtix 1.2.8.

Are you upgrading from sbtix 1.x? Read [MIGRATION.md](MIGRATION.md) first.

## Why?

Currently, this should mean that you won't have to redownload the world for each rebuild.

Additionally, this means that Nix can do a better job of enforcing purity where required. Ideally the build script itself should not communicate with the outer world at all, since otherwise Nix does not allow proxy settings to propagate.

* Private (password-protected) Maven stores are supported.
* Ivy repositories and plugins are cached by Sbtix.

## Design Overview

When you run `sbtix genNix` / `genComposition`, the plugin orchestrates three steps:

1. **Collect dependencies:** Sbtix walks your sbt build, resolves everything with Coursier (using your resolvers + credentials), and emits a locked `repo.nix`. Private repositories keep their own namespace (e.g. `nix-private-demo`) so you can see where each artifact originates. For Scala 2.13 and Scala 3 builds, sbtix also locks sbt tooling such as `org.scala-lang:scala2-sbt-bridge` / `org.scala-lang:scala3-sbt-bridge` so sbt can compile offline in the Nix sandbox.
2. **Generate the composition:** The plugin now renders **`sbtix-generated.nix`**, the machine-written derivation that wires together `repo.nix`, the plugin bootstrap, and the staging/install logic. If there is no `default.nix` in your project yet, sbtix drops in a tiny example that simply imports `./sbtix-generated.nix`. Your own `default.nix` is never overwritten again.
3. **Consume from Nix:** Import the generated file (`import ./sbtix-generated.nix { inherit pkgs; }`) from your project’s `default.nix`, or call the helpers in `plugin/nix-exprs/sbtix.nix` (`buildSbtProgram`, `buildSbtLibrary`, etc.) if you need a different layout. Either way, the derivation reads the locked `repo.nix` files and the staged application copied in step 2, so builds are reproducible and network-free. The Nix build environment also sets `SBTIX_NIX_BUILD=1` and `-Dsbtix.nixBuild=true` so your build can disable tasks that would otherwise try to download tooling (e.g. scalafmt).

This pipeline keeps your sbt workflow fast (dependency metadata is cached) while ensuring the Nix derivation is always in sync with what the sbt plugin produces.

## Why not? (caveats)

* sbtix 2 supports sbt 2.x builds only. Please report any regressions or missing offline artifacts.
* Some sbt-internal tooling artifacts are not declared as normal project/library dependencies. sbtix locks the Scala 2.13 / Scala 3 compiler bridge (`org.scala-lang:scala2-sbt-bridge` / `org.scala-lang:scala3-sbt-bridge`) automatically, but you may still need to add rare missing artifacts to `manual-repo.nix`.
* When sbtix enriches POM metadata for offline builds, it only fetches missing parent/BOM metadata from Maven Central. Private and Ivy repository metadata is locked when Coursier has already resolved or cached it; otherwise, add the missing metadata to `manual-repo.nix` so generation does not perform surprising authenticated network fetches.
* sbtix detects `sbt-scalafmt` in `project/plugins.sbt` and locks the configured `.scalafmt.conf` formatter runtime automatically, so `scalafmtOnCompile` can run in sandboxed Nix builds. For other plugins that download tooling at runtime, either disable non-build tasks when `SBTIX_NIX_BUILD=1` / `-Dsbtix.nixBuild=true` is set, or add the missing artifacts to `manual-repo.nix`.
* **The sbt launcher is locked per project.** Before a build loads, the sbt launcher fetches sbt itself (`org.scala-sbt:sbt`) and the Scala version sbt runs on. sbtix locks those artifacts for the `sbt.version` in your `project/build.properties`, so the launcher can start in the Nix sandbox. When you change `sbt.version`, regenerate the lock files.
* **Build from a clean source tree.** sbt 2 cannot read two kinds of entries inside the Nix sandbox: the `target` directories, which it fills with symlinks into its cache, and the `result` links of earlier Nix builds. A source that contains them can fail to build. Flake builds only see files tracked by git, and `sbtix-generated.nix` filters both out. In a Nix expression of your own that uses a working directory as `src`, write `src = sbtix.withoutSbtOutput (pkgs.lib.cleanSource ./.);`. `cleanSource` drops the `result` links, and `withoutSbtOutput` drops the `target` directories.

## How?

Sbtix is intended to be used as a Nix-provided command-line tool. The tool
runs sbt on your build with the sbtix plugin added, and generates `repo.nix`
and `sbtix-generated.nix`. In normal projects, you should not add
sbtix to `project/plugins.sbt` yourself; using the Nix CLI keeps the plugin jar,
Nix builders, templates, and generated source pin on the same sbtix version or
commit.

Direct sbt-plugin use is mainly for sbtix development or custom internal
workflows where you deliberately publish/provide the plugin artifact yourself
and take responsibility for keeping it in sync with the Nix expressions.

To install sbtix either:

```bash
nix shell github:natural-transformation/sbtix
```

Or, from this repository:

```bash
nix build '.#sbtix'
./result/bin/sbtix --help
```

sbtix provides a number of scripts to generate and update Nix expressions to fetch your dependencies and build your sbt project. These scripts open your project in an sbt process of their own, with the sbtix plugin added (see [How sbtix runs sbt](#how-sbtix-runs-sbt)). After generation, you don't need the sbtix command-line tools to actually build your project from nix.

### Using sbtix from a flake (recommended)

If your project is already a Nix flake, adding sbtix as a flake input is the most
reliable way to keep generation reproducible and flake-pure-safe.

**Full runnable example:** [`examples/flake-app/`](examples/flake-app/) builds a
minimal app with `nix build` using the flake-native workflow (`sbtix-gen` +
`buildSbtProgram`). Copy [`flake.nix.example`](flake.nix.example) into your own
project for a starting template.

Add an input and expose the sbtix CLI in your dev shell:

```nix
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    # Prefer a tag (or pinned commit) so the sbtix input is stable.
    sbtix.url = "github:natural-transformation/sbtix/v2.0.0";

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

Then choose the workflow that owns your Nix build expression:

```bash
nix develop
sbtix-gen
```

Use `sbtix-gen` when your flake imports sbtix's helper functions from the flake
input and defines the package itself. This generates the `repo.nix` lockfiles
that your flake imports; it does not generate or require `sbtix-generated.nix`.

Use `sbtix-gen-all2` when you want sbtix to generate a standalone Nix
composition:

```bash
nix develop
sbtix-gen-all2
```

That path generates `sbtix-generated.nix` and `sbtix.nix` in addition to the
`repo.nix` lockfiles. Commit those files only if your project actually imports
the generated composition, for example through `default.nix`.

When sbtix is run from a flake-provided binary, `sbtix-generated.nix` embeds a
pinned sbtix source reference (`builtins.fetchTree` / `fetchgit`) so downstream
flake evaluation stays pure.

To call sbtix's Nix helpers directly from your flake, import them from the sbtix
input and pick the builder that matches your packaging layout:

```nix
let
  sbtixLib = pkgs.callPackage "${sbtix}/plugin/nix-exprs/sbtix.nix" {
    jdk = pkgs.jdk;
    jre = pkgs.jdk;
  };
in
  sbtixLib.buildSbtProject {
    name = "my-app";
    # `withoutSbtOutput` leaves sbt's `target` directories out of the build.
    src = sbtixLib.withoutSbtOutput (pkgs.lib.cleanSource ./.);
    repo = [
      (import ./manual-repo.nix)
      (import ./repo.nix)
      (import ./project/repo.nix)
      (import ./project/project/repo.nix)
    ];
    installPhase = ''
      sbt --server assembly
      mkdir -p $out/lib
      # sbt 2 writes build output below target/out/jvm/scala-<version>/<name>/
      find target -name 'my-app-assembly-*.jar' -exec cp {} $out/lib/ \;
    '';
  }
```

`buildSbtProject` compiles the project in its build phase. Put your own sbt
tasks in `installPhase`, as above, so they run after that setup.

Always call `sbt --server <task>` in a phase of your own. A bare `sbt <task>`
makes the sbt 2 runner use its thin client, which starts a background sbt
server. That is slower, and it can fail inside the Nix sandbox. `--server` runs
sbt in the foreground instead. The built-in phases already do this.

For a project with an sbt-native-packager stage task, use `buildSbtProgram`.
For a library, use `buildSbtLibrary`. For custom layouts such as assembly jars,
use `buildSbtProject` with an explicit `installPhase`.

See [`examples/flake-app/flake.nix`](examples/flake-app/flake.nix) for a complete
`devShells` + `packages.default` setup.

### Sbtix commands

 * `sbtix` - runs sbt on your build with the sbtix plugin added. Arguments are passed on to sbt, so `sbtix genNix` and `sbtix genComposition` run the sbtix tasks. Without arguments you get an sbt shell.
 * `sbtix-gen` - generates `repo.nix` lockfiles (including nested `project/**/repo.nix` layers as needed). Internally runs: `sbtix genNix`.
 * `sbtix-gen-all` - kept for backwards compatibility. Internally runs: `sbtix genNix`.
 * `sbtix-gen-all2` - generates `repo.nix` lockfiles (including nested `project/**/repo.nix` layers) **and runs `genComposition`** to render `sbtix-generated.nix`. Internally runs: `sbtix genComposition`.

#### How sbtix runs sbt

Every sbtix command starts an sbt process of its own. This is what it does, and
what it leaves alone:

* **It needs an sbt 2.x build.** If `project/build.properties` names an sbt 1.x
  version, sbtix stops with a message and changes nothing.
* **It does not use an sbt server.** A plain `sbt` command in an sbt 2 build is
  a thin client that talks to a background sbt server. sbtix does not connect
  to that server and does not start one. It runs sbt in the foreground and
  exits when the commands are done. So you can run sbtix while an sbt server or
  your IDE is running for the same build. That server keeps working, and sbtix
  leaves no process behind.
* **It uses its own sbt directories.** sbt's global base is `$HOME/.sbtix`, and
  the Coursier cache is `$HOME/.sbtix/.coursier-cache`. Your personal global
  sbt plugins and settings are not loaded, so they do not end up in the lock
  files. The directory only holds downloads, sbt's caches, and `plugin-repo`,
  a link to the plugin of the sbtix you ran last. You can delete
  `$HOME/.sbtix` at any time.
* **It loads the plugin of the sbtix you run.** sbt looks for the plugin in
  the sbtix package first, before the other resolvers of your build. A plugin
  with the same version in `~/.ivy2/local` does not replace it. sbtix does
  not change anything in `~/.ivy2`.
* **It adds the plugin through temporary files.** While sbtix runs, your build
  holds `project/sbtix-auto-plugin-<pid>.sbt` and
  `project/project/sbtix-auto-plugin-<pid>.sbt`. Next to every `metals.sbt`
  that Metals generated under `project/`, it also adds
  `zz-sbtix-generated-ide-plugin-compat-<pid>.sbt`, which keeps IDE-only
  plugins out of the lock files. sbtix removes all of these files when it
  exits. If an sbt server or IDE looks at the build while they exist, it can
  reload the build definition; it reloads again once they are gone. If sbtix is
  killed hard (`kill -9`), delete the leftover files yourself.

By default, sbtix keeps verbose resolver diagnostics off for performance. To enable
the full `[SBTIX_DEBUG]` output, set `SBTIX_DEBUG=1` (or pass `-Dsbtix.debug=true`).

By default, generated locks include main artifacts plus the `tests` and `sources`
classifier artifacts, and skip `javadoc` jars to avoid extra downloads. To include
javadocs, add this to your sbt build:

```scala
sbtixArtifactClassifiers += "javadoc"
```

Set `sbtixArtifactClassifiers := Seq.empty` if you only want main artifacts.

#### Typical regeneration flow

Choose the regeneration flow that matches your Nix build entry point:

1. **Flake-native helper builds:** run `sbtix-gen` to regenerate every
   `repo.nix` layer (`repo.nix`, `project/repo.nix`,
   `project/project/repo.nix`). Commit those lockfiles so CI and teammates get
   the same dependencies. This is the usual flow when your flake imports
   `${sbtix}/plugin/nix-exprs/sbtix.nix` and calls `buildSbtProgram`,
   `buildSbtLibrary`, or `buildSbtProject` itself.
2. **Generated composition builds:** run `sbtix-gen-all2` to regenerate the
   `repo.nix` layers **and run `genComposition`** to render
   `sbtix-generated.nix`. Commit the generated composition files only when your
   project imports them as part of its build.
3. For generated composition builds, if you did not use `sbtix-gen-all2`, run
   `sbtix genComposition` to render `sbtix-generated.nix` using the shared
   template.
   - When sbtix is run from a flake-provided binary (or when `SBTIX_SOURCE_URL`, `SBTIX_SOURCE_REV`, and `SBTIX_SOURCE_NAR_HASH` are set), the generated file embeds a pinned `builtins.fetchTree`/`fetchgit` block that points back to the exact sbtix revision you used for generation. This keeps flake evaluation pure.
   - If those pins are not available, sbtix cannot emit a portable, flake-pure-safe source reference. In that case, regenerate using a flake-provided sbtix (or provide the pins) before running `nix build` in a downstream flake.
4. Commit the refreshed `repo.nix` files (and any generated `.nix` files that
   your build actually imports, e.g. `sbtix-generated.nix`) before running
   `nix build`.

### Creating a build

* For generated composition builds, run `sbtix genComposition` to have sbtix
  emit `sbtix-generated.nix` for you. The file is rendered from the same template
  used in our tests, so it already contains the sandbox-friendly `SBT_OPTS`,
  Ivy generation, and install logic. Check it in as-is and have your
  `default.nix` (or another entry point) simply import it.
* For flake-native or custom builds, call the helper functions from your flake
  input instead of importing the generated composition. Use `buildSbtProgram`
  for projects with a `stage` task, `buildSbtLibrary` for libraries, or
  `buildSbtProject` when you need a custom `installPhase`. In this flow, the
  generated `repo.nix` files are the source-control lockfiles;
  `sbtix-generated.nix` and `sbtix.nix` are not required unless your build
  imports them.

If you want a starting point, copy `default.nix.example` from this repository into your project. It simply imports the generated composition:

```nix
{ pkgs ? import <nixpkgs> {} }:

import ./sbtix-generated.nix { inherit pkgs; }
```

The root-level `default.nix` now just raises an error so it no longer looks like part of the build. If you need to hand-roll the derivation instead, use the lower-level API directly:

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
        # `withoutSbtOutput` leaves the `target` directories of a working tree
        # out of the build. See the caveats above.
        src = sbtix.withoutSbtOutput (pkgs.lib.cleanSource ./.);
        repo = [ (import ./manual-repo.nix)
                 (import ./repo.nix)
                 (import ./project/repo.nix)
                 (import ./project/project/repo.nix)
               ];
    }
```

* generate your repo.nix files with one of the commands listed above.
* if you use the generated composition path, rerun `sbtix genComposition` after
  changing dependencies to regenerate `sbtix-generated.nix` so it stays in sync
  with both the template and the sbtix revision you are using.
* check the generated nix files that your build imports into your source control.
* finally, run `nix-build` for generated composition builds or `nix build .#...`
  for flake-native builds.
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

Programs must stage an application with `Universal / stage` or `stage`. By
default `buildSbtProgram` tries both commands and copies staged output from
`target/universal/stage` or sbt 2-style `target/out/.../universal/stage` into
the Nix build output. Override `stageCommands` when a project uses a custom
packaging task.

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

In order to use a private repository, add its credentials to sbt's `credentials` setting:

```scala
credentials += Credentials(
  realm = "My realm",
  host = "repo.example.com",
  userName = "me",
  passwd = "secret"
)
```

sbtix picks the credentials by the host of the repository. Set them in every
subproject that resolves from the repository; see
`plugin/src/sbt-test/sbtix/private-auth/build.sbt` for an example.

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
resolvers += Resolver.uri(
  "sbt-plugin-releases",
  uri("https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/")
)(using Resolver.ivyStylePatterns)
```

Q: When I `nix-build` it sbt complains `java.io.IOException: Cannot run program "git": error=2, No such file or directory`

A: You are likely depending on a project via git. This is not recommended because it can make builds non-deterministic. Prefer replacing the git dependency with a pinned source dependency or a normal repository artifact. If your build really needs to shell out to git, pass it as a build input in your Nix expression:

```nix
sbtix.buildSbtProgram {
  # ...
  buildInputs = [ pkgs.git ];
}
```

Q: How do I keep my own `default.nix`?

A: Keep your own `default.nix` in the project. `genComposition` writes `sbtix-generated.nix` every time, but it only writes an example `default.nix` when one does not already exist.

Q: How do I use a different type of SBT build in `default.nix`

A: The generated composition uses `sbtix.buildSbtProgram`, which expects a native-packager-style staged application. For libraries, call `sbtix.buildSbtLibrary` directly from your own `default.nix`; for custom layouts, call `sbtix.buildSbtProject` with an explicit `installPhase`.

## Thanks

- [Natalie Klestrup Röijezon](https://gitlab.com/nightkr) - For creating Sbtix
- [Eelco Dolstra](https://github.com/edolstra) - For getting this whole Nix thing started
- [Charles O'Farrel](https://github.com/charleso) - For writing [sbt2nix](https://github.com/charleso/sbt2nix)
- [Chris Van Vranken](https://github.com/cessationoftime) - For sorting out a lot of dependency-fetching bugs, and adding SBT plugin support
- [Maximilian Bosch](https://github.com/Ma27) - For fixing the UX of this thing
