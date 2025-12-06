package se.nullable.sbtix

import java.math.BigInteger
import java.util.Base64

/** Hash conversion helpers shared between the sbt plugin and tests. */
object SbtixHashes {
  // `builtins.fetchTree` accepts SRI hashes (sha256-base64) while `fetchgit`
  // expects the legacy nix-base32 representation. Keeping the converter in code
  // avoids shelling out to `nix hash` (which is not available inside sbt).
  private val NixBase32Alphabet = "0123456789abcdfghijklmnpqrsvwxyz"
  private val NixBase = BigInteger.valueOf(32)
  private val TargetLength = 52 // nix-base32 encoded sha256 hashes are 52 chars

  /** Converts an SRI string (sha256-base64) into the nix-base32 variant used by
    * `fetchgit`. This keeps the fallback path self-contained (no external hash
    * tooling, no Nix dependency inside sbt).
    */
  def sriToNixBase32(sri: String): Option[String] = {
    val Prefix = "sha256-"
    if (!sri.startsWith(Prefix)) None
    else {
      val base64Segment = sri.substring(Prefix.length)
      val decodedBytes = try {
        Base64.getDecoder.decode(base64Segment)
      } catch {
        case _: IllegalArgumentException => return None
      }
      Some(bytesToNixBase32(decodedBytes))
    }
  }

  private def bytesToNixBase32(bytes: Array[Byte]): String = {
    // Nix treats hashes as little-endian numbers when generating nix-base32.
    // Convert to little-endian before feeding into the BigInteger divider loop.
    var value = new BigInteger(1, bytes.reverse)
    val builder = new StringBuilder

    while (value.signum() > 0) {
      val Array(div, rem) = value.divideAndRemainder(NixBase)
      builder.append(NixBase32Alphabet.charAt(rem.intValue()))
      value = div
    }

    val encoded =
      if (builder.isEmpty) "0"
      else builder.reverse.toString

    val paddingLength = TargetLength - encoded.length
    val padding =
      if (paddingLength > 0) "0" * paddingLength else ""

    padding + encoded
  }
}

