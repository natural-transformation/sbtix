package se.nullable.sbtix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NixWriter2Spec extends AnyFlatSpec with Matchers {

  "NixWriter2" should "deduplicate generated artifact keys" in {
    val relativePath =
      "com/github/bigwheel/util-backports_2.12/2.1/util-backports-2.1.pom"
    val crossSuffixedUrl =
      "https://repo1.maven.org/maven2/com/github/bigwheel/util-backports_2.12/2.1/util-backports_2.12-2.1.pom"
    val matchingUrl =
      "https://repo1.maven.org/maven2/com/github/bigwheel/util-backports_2.12/2.1/util-backports-2.1.pom"

    val output = NixWriter2(
      Set.empty,
      Set(
        NixArtifact("nix-public", relativePath, crossSuffixedUrl, "deadbeef"),
        NixArtifact("nix-public", relativePath, matchingUrl, "feedface")
      ),
      "2.12",
      "1.0"
    )

    val generatedKey = "\"nix-public/com/github/bigwheel/util-backports_2.12/2.1/util-backports-2.1.pom\" = {"
    output.linesIterator.count(_.contains(generatedKey)) shouldBe 1
    output should include(s"""url = "$matchingUrl";""")
    output should not include crossSuffixedUrl
  }

  it should "choose a deterministic duplicate artifact when no URL filename matches" in {
    val output = NixWriter2(
      Set.empty,
      Set(
        NixArtifact("nix-public", "org/example/demo/1.0/demo-1.0.pom", "https://repo.example/b/demo-other.pom", "feedface"),
        NixArtifact("nix-public", "org/example/demo/1.0/demo-1.0.pom", "https://repo.example/a/demo-cross.pom", "deadbeef")
      ),
      "2.12",
      "1.0"
    )

    output should include("""url = "https://repo.example/a/demo-cross.pom";""")
    output should not include "https://repo.example/b/demo-other.pom"
  }

  it should "deduplicate content-equivalent Maven artifacts across resolver namespaces" in {
    val relativePath =
      "org/scala-sbt/actions_3/2.0.0/actions_3-2.0.0.jar"
    val publicUrl =
      "https://repo1.maven.org/maven2/org/scala-sbt/actions_3/2.0.0/actions_3-2.0.0.jar"
    val staleMirrorUrl =
      "https://jaspersoft.jfrog.io/jaspersoft/third-party-ce-artifacts/org/scala-sbt/actions_3/2.0.0/actions_3-2.0.0.jar"
    val sha256 =
      "5E79E88F19C092BE337AF993C77AB09E6035B627000000000000000000000000"

    val output = NixWriter2(
      Set(NixRepo("nix-public"), NixRepo("nix-jaspersoft")),
      Set(
        NixArtifact("nix-public", relativePath, publicUrl, sha256),
        NixArtifact("nix-jaspersoft", relativePath, staleMirrorUrl, sha256)
      ),
      "3.6.4",
      "2.0.0"
    )

    val publicKey = s""""nix-public/$relativePath" = {"""
    val staleMirrorKey = s""""nix-jaspersoft/$relativePath" = {"""

    output.linesIterator.count(line => line.contains(publicKey) || line.contains(staleMirrorKey)) shouldBe 1
    output should include(s"""url = "$publicUrl";""")
    output should not include staleMirrorUrl
  }
}
