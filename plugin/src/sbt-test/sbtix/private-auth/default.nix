{ pkgs ? import <nixpkgs> {}
, cleanSource ? src: pkgs.lib.cleanSourceWith { filter = path: type: baseNameOf path != "target"; inherit src; }
}:
let
    sbtix = pkgs.callPackage ./sbtix.nix {};
in
    sbtix.buildSbtProject {
        name = "sbtix-private-auth";
        src = cleanSource ./.;
        repo = [ (import ./repo.nix)
                 (import ./project/repo.nix)
                 (import ./manual-repo.nix)
               ];

        installPhase =''
          sbt three/stage
          cp -r three/target/universal/stage $out
        '';
    }
