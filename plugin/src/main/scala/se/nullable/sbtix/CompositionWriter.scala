package se.nullable.sbtix

object CompositionWriter {
  def apply(compositionType: String, buildName: String): String =
    stripWhitespace(
      s"""
         |# this file originates from SBTix
         |{ pkgs ? import <nixpkgs> {}
         |, cleanSource ? pkgs.lib.cleanSource
         |, gitignoreLib ? pkgs.nix-gitignore
         |}:
         |
         |let
         |  sbtix = pkgs.callPackage ./sbtix.nix {};
         |in
         |  sbtix.buildSbt${compositionType.capitalize} {
         |    name = "$buildName";
         |    src = cleanSource (gitignoreLib.gitignoreSource ./.);
         |    #sbtixBuildInputs = pkgs.callPackage ./sbtix-build-inputs.nix { inherit pkgs; };
         |    repo = [
         |      (import ./repo.nix)
         |      (import ./project/repo.nix)
         |      (import ./manual-repo.nix)
         |    ];
         |  }
     """.stripMargin
    )

  private def stripWhitespace(t: String) =
    t.split("\n") map { (l: String) =>
      l.replaceAll("^\\s+$", "")
    } mkString "\n"
}
