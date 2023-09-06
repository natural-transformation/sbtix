{ pkgs ? import <nixpkgs> {}
, cleanSource ? pkgs.lib.cleanSource
}:

let
  sbtix = pkgs.callPackage ../../../plugin/nix-exprs/sbtix.nix {};
in
  sbtix.buildSbtLibrary {
    name = "sbtix-multibuild-two";
    src = cleanSource ./.;
    repo = [
      (import ./repo.nix)
      (import ./project/repo.nix)
      (import ./manual-repo.nix)
    ];
    sbtixBuildInputs = pkgs.callPackage ./sbtix-build-inputs.nix {};
  }
