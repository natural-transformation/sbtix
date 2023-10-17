{
  description = "Generates a lock file for SBT and build your project in Nix with it";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    hercules-ci-effects.url = "github:hercules-ci/hercules-ci-effects";
  };

  outputs = inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      imports = [
        inputs.hercules-ci-effects.flakeModule
        ./tests/flake-module.nix
      ];
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      perSystem = { config, self', inputs', pkgs, system, ... }: {
        packages.default = import ./default.nix { inherit pkgs; };

        devShells.default = pkgs.mkShell {
          nativeBuildInputs = [
            pkgs.sbt

            # See CONTRIBUTING.md
            pkgs.hci
          ];
          # TODO: Don't rely on NIX_PATH in tests.
          NIX_PATH = "nixpkgs=${inputs.nixpkgs}";
        };
      };
      herculesCI = {
        ciSystems = [ "x86_64-linux" "aarch64-linux" ];
      };
      flake = {
        # The usual flake attributes can be defined here, including system-
        # agnostic ones like nixosModule and system-enumerating ones, although
        # those are more easily expressed in perSystem.
      };
    };
}
