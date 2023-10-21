# this file originates from SBTix
{ pkgs ? import <nixpkgs> {}
, cleanSource ? pkgs.lib.cleanSource
, gitignore ? (let
                  src = pkgs.fetchFromGitHub { 
                    owner = "hercules-ci";
                    repo = "gitignore.nix";
                    # put the latest commit sha of gitignore Nix library here:
                    rev = "9e21c80adf67ebcb077d75bd5e7d724d21eeafd6";
                    # use what nix suggests in the mismatch message here:
                    sha256 = "sha256:vky6VPK1n1od6vXbqzOXnekrQpTL4hbPAwUhT5J9c9E=";
                  };
                in import src { inherit (pkgs) lib; })

}:

let
  sbtix = pkgs.callPackage ../../../plugin/nix-exprs/sbtix.nix {};
in
  sbtix.buildSbtLibrary {
    name = "sbtix-multibuild-two";
    src = cleanSource ./.;
    repo = [
      (import ./repo.nix)
      (import ./project/repo.nix)
      (import ./manual-repo.nix)
    ];
  }
