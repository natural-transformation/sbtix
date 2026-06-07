{
  description = "Minimal sbt project built with sbtix from a Nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    sbtix.url = "github:natural-transformation/sbtix/v1.1.1";
    sbtix.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, sbtix, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in {
          default = pkgs.mkShell {
            packages = [ sbtix.packages.${system}.sbtix ];
          };
        });

      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          sbtixLib = pkgs.callPackage "${sbtix}/plugin/nix-exprs/sbtix.nix" {
            jdk = pkgs.jdk;
            jre = pkgs.jdk;
          };
        in {
          default = sbtixLib.buildSbtProgram {
            name = "flake-app-example";
            src = pkgs.lib.cleanSource ./.;
            repo = [
              (import ./manual-repo.nix)
              (import ./repo.nix)
              (import ./project/repo.nix)
              (import ./project/project/repo.nix)
            ];
          };
        });
    };
}
