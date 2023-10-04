{ inputs, lib, withSystem, ... }: {
  herculesCI = { ... }: {
    onPush.default = {
      outputs = {
        effects = {
          tests = withSystem "x86_64-linux" ({ config, pkgs, hci-effects, ... }:
            let
              base = {
                src = ../.;
                env.postUnpack = ''
                  patchShebangs --build $(find . -type f -name '*.sh')
                '';
                # TODO (sbtix flake support): NIX_PATH dependency should be removed
                env.NIX_PATH = "nixpkgs=${inputs.nixpkgs}";
                inputs = [
                  config.packages.default
                  pkgs.sbt
                  pkgs.nix
                ];
              };
            in
            {
              multi-build =
                hci-effects.modularEffect {
                  imports = [ base ];
                  effectScript = ''
                    ./tests/multi-build/run.sh
                  '';
                };
              template-generation =
                hci-effects.modularEffect {
                  imports = [ base ];
                  effectScript = ''
                    ./tests/template-generation/run.sh
                  '';
                };
            });
        };
      };
    };
  };
}