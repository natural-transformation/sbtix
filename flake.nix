{
  description = "Generates a lock file for SBT and build your project in Nix with it";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          selfSourceInfo = self.sourceInfo or {};
          sbtixPackage = pkgs.callPackage ./sbtix-tool.nix {
            inherit selfSourceInfo;
          };
        in {
          sbtix = sbtixPackage;
          default = sbtixPackage;
        });

      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in {
          default = pkgs.mkShell {
            nativeBuildInputs = [
              pkgs.nix
              pkgs.sbt
            ];
            # TODO: Don't rely on NIX_PATH in tests.
            NIX_PATH = "nixpkgs=${nixpkgs}";
          };
        });
    };
}
