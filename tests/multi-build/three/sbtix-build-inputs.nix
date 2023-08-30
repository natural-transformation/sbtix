{ pkgs ? import <nixpkgs> {} }:

pkgs.symlinkJoin {
  name = "sbtix-dependencies";

  paths = [
    (pkgs.callPackage ../one {})
    (pkgs.callPackage ../two {})
  ];
}
