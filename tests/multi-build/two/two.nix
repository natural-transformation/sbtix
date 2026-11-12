{ sbtix, pkgs ? import <nixpkgs> {} }:
let
  inherit (pkgs.lib) optional;
  manualRepo = import ./manual-repo.nix;
  repoLock = import ./repo.nix;
  projectRepo = import ./project/repo.nix;

  buildInputsPath = ./sbtix-build-inputs.nix;
  sbtixInputs =
    if builtins.pathExists buildInputsPath
    then pkgs.callPackage buildInputsPath {}
    else "";
in
sbtix.buildSbtLibrary {
  name = "sbtix-multibuild-two";
  # `cleanSource` drops the `result` links of earlier Nix builds, and
  # `withoutSbtOutput` drops sbt's `target` directories. sbt 2 cannot read
  # either of them inside the Nix sandbox.
  src = sbtix.withoutSbtOutput (pkgs.lib.cleanSource ./.);
  repo = [ repoLock projectRepo manualRepo ];
  sbtixBuildInputs = sbtixInputs;
}

