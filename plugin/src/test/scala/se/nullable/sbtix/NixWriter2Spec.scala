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
}
