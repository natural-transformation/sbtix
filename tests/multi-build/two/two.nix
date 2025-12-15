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
  src = ./.;
  repo = [ repoLock projectRepo manualRepo ];
  sbtixBuildInputs = sbtixInputs;
}

