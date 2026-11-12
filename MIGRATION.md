# Migrating to sbtix 2

This guide is for projects that use sbtix 1.x. It covers what you have to do,
and what changed.

## What sbtix 2 is

sbtix 2 supports **sbt 2.x builds only**. sbt 1.x support is gone.

| Your build | Use |
| --- | --- |
| sbt 2.x | sbtix 2 (this guide) |
| sbt 1.x, and you can move to sbt 2 | move the build to sbt 2 first, then follow this guide |
| sbt 1.x, and you stay there | sbtix 1.2.8 (see [Staying on sbt 1.x](#staying-on-sbt-1x)) |

sbtix 2.0.0 is built and tested with sbt 2.0.12. It also generates locks for a
build on sbt 2.0.0.

## What you need

- A build on sbt 2.x: `sbt.version=2.0.12` in `project/build.properties`.
  The sbt project has a guide for the build files:
  [Migrating from sbt 1.x](https://www.scala-sbt.org/2.x/docs/en/changes/migrating-from-sbt-1.x.html).
- JDK 17 or newer. sbt 2 does not start on an older JDK.
- For the Nix builders: an `sbt` package that knows the `--server` argument.
  sbt 2.x from nixpkgs is the tested choice (nixos-unstable has sbt 2.0.12).

## Steps

### 1. Update the sbtix input

```nix
sbtix.url = "github:natural-transformation/sbtix/v2.0.0";
```

Then update the lock:

```bash
nix flake update sbtix
```

### 2. Regenerate the lock files

Use the same command as before, from your dev shell:

```bash
sbtix-gen
```

or, if your build imports `sbtix-generated.nix`:

```bash
sbtix-gen-all2
```

You do not have to stop a running sbt server or close your IDE first. See
[The CLI runs sbt on its own](#the-cli-runs-sbt-on-its-own).

Commit the regenerated files: `repo.nix`, `project/repo.nix`,
`project/project/repo.nix`, and for generated compositions also
`sbtix-generated.nix` and `sbtix.nix`.

### 3. Delete `sbtix-plugin-repo.nix`

sbtix 1.x wrote this file next to `sbtix-generated.nix`. sbtix 2 does not write
it and does not read it. Remove it from your project:

```bash
git rm sbtix-plugin-repo.nix
```

### 4. Check Nix phases you wrote yourself

This only matters if you call `buildSbtProject` with your own `buildPhase` or
`installPhase`.

Call sbt as `sbt --server <task>`:

```nix
installPhase = ''
  sbt --server assembly
  mkdir -p $out/lib
  find target -name 'my-app-assembly-*.jar' -exec cp {} $out/lib/ \;
'';
```

Two things changed with sbt 2:

- A bare `sbt <task>` uses the sbt thin client, which starts a background sbt
  server. That can fail in the Nix sandbox. `--server` runs sbt in the
  foreground.
- Build output moved from `target/scala-<version>/` to
  `target/out/jvm/scala-<version>/<project>/`. Adjust paths that copy jars.

### 5. Check Nix expressions you wrote yourself

This only matters if you call the builders from `sbtix.nix` in your own Nix
files.

- **A working directory as `src`.** sbt 2 fills `target` directories with
  symlinks into its cache. They do not exist in the Nix sandbox, and the build
  can fail on them. Filter them out:

  ```nix
  src = sbtix.withoutSbtOutput (pkgs.lib.cleanSource ./.);
  ```

  Keep `cleanSource` in that expression. It drops the `result` links of
  earlier Nix builds, which sbt 2 cannot read in the sandbox either.

  A flake build only sees files tracked by git, so it is not affected.
  `sbtix-generated.nix` applies both filters for you.
- **`extraPluginRepos`.** `sbtix.nix` no longer takes this argument. Remove it
  from your `callPackage` call. If you need extra artifacts, add a lock file to
  the `repo` list of the builder.

## What changed

### sbt 1.x builds are rejected

`sbtix` reads `sbt.version` from `project/build.properties`. If it is not a 2.x
version, sbtix prints a message and stops. It does not change any file.

### The CLI runs sbt on its own

sbtix 1.x started sbt the default way. For an sbt 2 build that is a thin client
that sends commands to a background sbt server. Two things went wrong:

- If a server was already running for the build, the sbtix commands went to
  that server. It had not loaded the sbtix plugin, so the run failed with
  `Not a valid key: genNix`.
- If no server was running, sbtix started one. It kept running after sbtix was
  done, with settings that only made sense for sbtix.

sbtix 2 runs sbt in the foreground, in a process of its own, and never starts
or contacts an sbt server. You can run it next to a running sbt server or IDE.
The temporary plugin files that sbtix puts into `project/` while it runs only
have an effect in its own sbt process. A server or IDE that reloads the build
in that time gets nothing from them.

### Fewer changes to your home directory

- sbtix 1.x replaced `~/.ivy2/local/se.nullable.sbtix` and deleted
  `~/.ivy2/cache/se.nullable.sbtix` on every run. sbtix 2 does not touch
  `~/.ivy2`.
- sbtix still keeps its downloads in `~/.sbtix`. sbtix 1.x also kept a global
  plugin definition there (`~/.sbtix/plugins`). sbtix 2 removes that directory
  when it runs, because sbt 2 would still load it.
- sbtix 2 keeps one link in `~/.sbtix`: `plugin-repo` points at the plugin of
  the sbtix you ran last. sbt loads the plugin through that link, so a build
  never keeps using the plugin of an older sbtix.

### sbt is locked per project

sbtix 1.x shipped one lock file with the launcher artifacts of one sbt 1.x
version (`sbtix-plugin-repo.nix`). A build on another sbt 1.x version could
fail offline.

sbtix 2 has no such file. `genNix` locks the launcher artifacts for the
`sbt.version` of your build. After you change `sbt.version`, regenerate the
lock files.

### Generated compositions

`sbtix-generated.nix` changed in two ways:

- It no longer imports `sbtix-plugin-repo.nix`.
- It leaves `target` directories out of the source.

A composition that sbtix 1.x generated keeps pointing at the sbtix 1.x source
it was generated with. It is not affected until you regenerate it.

### Files removed from the sbtix repository

Only relevant if your Nix code imported them by path:

- `plugin/sbtix-plugin-repo.nix`
- `plugin/repo-sbt2.nix` (merged into `plugin/repo.nix`)
- `plugin/nix-exprs/plugin-bootstrap-snippet.nix`

### Versions

| | sbtix 1.2.8 | sbtix 2.0.0 |
| --- | --- | --- |
| Supported builds | sbt 1.x and sbt 2.x | sbt 2.x |
| Built and tested with | sbt 1.12.11, sbt 2.0.0 | sbt 2.0.12 |
| Plugin artifact | `sbtix` (sbt 1), `sbtix_sbt2_3` (sbt 2) | `sbtix_sbt2_3` |
| Scala version of the plugin | 2.12 and 3.8.4 | 3.8.4 |
| coursier | 2.1.24 | 2.1.26 |

The plugin stays on Scala 3.8.4 on purpose. sbt 2.0.x itself runs on Scala
3.8.4, and it cannot load a plugin that was compiled with a newer Scala 3
minor. Your own project can use any Scala version sbt 2 supports; the tests
cover Scala 3.9.0 and 2.13.

## Staying on sbt 1.x

Keep the sbtix input on the last 1.x release:

```nix
sbtix.url = "github:natural-transformation/sbtix/v1.2.8";
```

One thing to know: sbtix 1.2.8 uses `lib.fold`, which recent nixpkgs revisions
removed. With such a nixpkgs, evaluation stops with
`fold is a removed alias of foldr`. Either keep an older nixpkgs for the build,
or replace `fold` with `foldr` in the `sbtix.nix` file that sbtix generated in
your project.
