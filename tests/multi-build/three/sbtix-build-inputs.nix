{ pkgs ? import <nixpkgs> {} }:

pkgs.symlinkJoin {
  name = "sbtix-dependencies";

  paths =
    let
      sbtixOne = pkgs.callPackage ../one/sbtix.nix {};
      sbtixTwo = pkgs.callPackage ../two/sbtix.nix {};
    in [
      (pkgs.callPackage ../one/one.nix { sbtix = sbtixOne; })
      (pkgs.callPackage ../two/two.nix { sbtix = sbtixTwo; })
    ];
}
