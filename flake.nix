{
  description = "Generates a lock file for SBT and build your project in Nix with it";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      perSystem = { pkgs, ... }:
        let
          selfSourceInfo = inputs.self.sourceInfo or {};
          sbtixPackage = pkgs.callPackage ./sbtix-tool.nix {
            inherit selfSourceInfo;
          };
        in {
          packages = {
            sbtix = sbtixPackage;
            default = sbtixPackage;
          };

          devShells.default = pkgs.mkShell {
            nativeBuildInputs = [
              pkgs.nix
              pkgs.sbt
            ];
            # TODO: Don't rely on NIX_PATH in tests.
            NIX_PATH = "nixpkgs=${inputs.nixpkgs}";
          };
        };
      flake = {
        # The usual flake attributes can be defined here, including system-
        # agnostic ones like nixosModule and system-enumerating ones, although
        # those are more easily expressed in perSystem.
      };
    };
}
