{ pkgs ? import <nixpkgs> {} }:

let
  fakeSbt = pkgs.writeShellScriptBin "sbt" ''
    echo "fake sbt: $*" 1>&2
    if [ "''${1:-}" = "stage" ]; then
      exit 1
    fi
    exit 0
  '';

  sbtix = pkgs.callPackage ./sbtix.nix {
    sbt = fakeSbt;
  };
in
  sbtix.buildSbtProgram {
    name = "library-diagnostic";
    src = ./.;
    repo = [];
    buildPhase = ''
      runHook preBuild
      runHook postBuild
    '';
  }
