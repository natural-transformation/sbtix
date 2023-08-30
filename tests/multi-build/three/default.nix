{ pkgs ? import <nixpkgs> {}
, cleanSource ? pkgs.lib.cleanSource
}:

let
  sbtix = pkgs.callPackage ../../../plugin/nix-exprs/sbtix.nix {};
in
  sbtix.buildSbtProgram {
    name = "sbtix-multibuild-three";
    src = cleanSource ./.;
    repo = [ (import ./manual-repo.nix)
             (import ./repo.nix)
             (import ./project/repo.nix)
           ];
    sbtixBuildInputs = pkgs.callPackage ./sbtix-build-inputs.nix {};
  }
