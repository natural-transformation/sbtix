# Sbtix

[![Build Status](https://github.com/github/docs/actions/workflows/main.yml/badge.svg?branch=master)](https://hercules-ci.com/github/natural-transformation/sbtix)

## What?

Sbtix generates a Nix definition that represents your SBT project's dependencies. It then uses this to build a Maven repo containing the stuff your project needs, and feeds it back to your SBT build.

## Why?

Currently, this should mean that you won't have to redownload the world for each rebuild.

Additionally, this means that Nix can do a better job of enforcing purity where required. Ideally the build script itself should not communicate with the outer world at all, since otherwise Nix does not allow proxy settings to propagate.

* Private (password-protected) Maven stores are supported.
* Ivy repositories and plugins are cached by Sbtix.

## Why not? (caveats)

* Alpha quality, beware (and please report any issues!)
* Nix file for SBT compiler interface dependencies must currently be created manually.

## How?

To install sbtix either:

```
nix shell github:natural-transformation/sbtix
```

Or clone the sbtix git repo and:

```
cd sbtix
nix-env -f . -i sbtix
```

sbtix provides a number of scripts to generate and update Nix expressions to fetch your dependencies and build your sbt project. These scripts work by opening your project in sbt and loading an additional sbtix plugin (via the `sbt.global.base` directory to `$HOME/.sbtix`). After generation, you don't need the sbtix command-line tools to actually build your project from nix.

### Sbtix commands

 * `sbtix` - loads the sbtix global plugin and launches sbt. Sets `sbt.global.base` directory to `$HOME/.sbtix`.
 * `sbtix-gen` - gen build dependencies only, produces `repo.nix`. Alias: `sbtix genNix`.
 * `sbtix-gen-all` - gen build and plugin dependencies, produces `repo.nix` and `project/repo.nix`. Alias: `sbtix genNix "reload plugins" genNix`
 * `sbtix-gen-all2` - gen build, plugin and pluginplugin dependencies, produces `repo.nix`, `project/repo.nix`, and `project/project/repo.nix`. Alias: `sbtix genNix "reload plugins" genNix "reload plugins" genNix`

### Creating a build

 * create default.nix as shown below. Edit as necessary. This assumes that you're building a program using [sbt-native-packager](http://www.scala-sbt.org/sbt-native-packager/index.html), use `buildSbtLibrary` instead if you want to build a library or `buildSbtProject` if you want to use a free-form `installPhase`.

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
 * check the generated nix files into your source control.
 * finally, run `nix-build` to build!
 * any additional missing dependencies that `nix-build` encounters should be fetched with `nix-prefetch-url` and added to `manual-repo.nix`.

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

```
{ pkgs ? import <nixpkgs> {} }:

pkgs.callPackage ./path/to/dependency/derivation {}
```

.. or multiple:

```
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

```
sbtix.buildSbtProgram {
    ...
    sbtixBuildInputs = (pkgs.callPackage ./sbtix-build-inputs.nix {});
    ...
}
```

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

```
// if using PlayFramework
resolvers += Resolver.url("sbt-plugins-releases", url("https://dl.bintray.com/playframework/sbt-plugin-releases"))(Resolver.ivyStylePatterns)
```

Q: When I `nix-build` it sbt complains `java.io.IOException: Cannot run program "git": error=2, No such file or directory`

A: You are likely depending on a project via git.  This isn't recommended usage for sbtix since it leads to non-deterministic builds. However you can enable this by making two small changes to sbtix.nix, in order to make git a buildInput.

top of sbtix.nix with git as buildinput
```
{ runCommand, fetchurl, lib, stdenv, jdk, sbt, writeText, git }:
```

bottom of sbtix.nix with git as buildinput
```
buildInputs = [ jdk sbt git ] ++ buildInputs;
```

Q: How do I disable the generation of a `default.nix`?

A: You have to add `generateComposition := false` to your `build.sbt`.

Q: How do I use a different type of SBT build in `default.nix`

A: You can change the value of `compositionType` in your `build.sbt`. Allowed values are `program` and `library`. In the end the `sbtix.buildSbt{compositionType}` API in the nix expressions will be used.

## Thanks

- [Teo Klestrup Röijezon](https://gitlab.com/teozkr) - For creating Sbtix
- [Eelco Dolstra](https://github.com/edolstra) - For getting this whole Nix thing started
- [Charles O'Farrel](https://github.com/charleso) - For writing [sbt2nix](https://github.com/charleso/sbt2nix)
- [Chris Van Vranken](https://github.com/cessationoftime) - For sorting out a lot of dependency-fetching bugs, and adding SBT plugin support
- [Maximilian Bosch](https://github.com/Ma27) - For fixing the UX of this thing
