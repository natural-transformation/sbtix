{ pkgs ? import <nixpkgs> {} }:

let
  # Conditionally import repo.nix if it exists, otherwise provide a default empty structure.
  # Assuming ./repo.nix is relative to this default.nix file.
  sbtixRepo = if builtins.pathExists ./repo.nix
              then import ./repo.nix { 
                     inherit (pkgs) fetchurl fetchgit fetchzip; 
                   }
              else { repos = []; }; # Default structure if repo.nix is not found

  # Create the SBT derivation with our custom repos
  sbtWithRepo = pkgs.sbt.override {
    jre = pkgs.jdk11;
    # extraSbtArgs = (builtins.concatStringsSep " " 
    #   (map (repo: "-Dsbt.repository.config=${repo}/repositories") sbtixRepo.repos));
  };
  
in pkgs.stdenv.mkDerivation {
  name = "sbtix-project";
  src = ./.;
  
  buildInputs = [ sbtWithRepo pkgs.jdk11 ];

  shellHook = ''
    # Construct SBT_OPTS from sbtixRepo.repos. If sbtixRepo.repos is empty (e.g., repo.nix not found),
    # SBT_OPTS will be empty.
    export SBT_OPTS="${builtins.concatStringsSep " " (map (repoPath: "-Dsbt.repository.config=" + repoPath + "/repositories") sbtixRepo.repos)}"
    if [ -n "$SBT_OPTS" ]; then
      echo "SBT_OPTS set to: $SBT_OPTS"
    else
      echo "Warning: ./repo.nix not found or did not define any repos. SBT_OPTS is empty."
    fi
  '';
  
  buildPhase = ''
    sbt compile
  '';
  
  installPhase = ''
    mkdir -p $out
    cp -r target $out/
  '';
}
