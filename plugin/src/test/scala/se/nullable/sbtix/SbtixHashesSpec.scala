package se.nullable.sbtix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SbtixHashesSpec extends AnyFlatSpec with Matchers {

  "sriToNixBase32" should "convert sha256 SRI hashes to nix-base32" in {
    val sri = "sha256-6Dpe9T54US17zBXgQHqRoh6rpw93JWEKX84f7Uj0kD0="
    SbtixHashes.sriToNixBase32(sri) shouldBe Some("0gchyi4fs7yfbw5629bp1yksn7m2j5x41q0mrixjslbq7vsmwfp8")
  }

  it should "return None for unsupported formats" in {
    SbtixHashes.sriToNixBase32("md5-deadbeef") shouldBe None
    SbtixHashes.sriToNixBase32("sha256-not-base64!") shouldBe None
  }
}

