package se.nullable.sbtix

import java.io.{File, FileInputStream}
import java.security.{DigestInputStream, MessageDigest}

/**
 * Represents an artifact in Nix format.
 * 
 * @param name Unique name for the artifact
 * @param url URL where the artifact can be downloaded from
 * @param sha256 SHA256 checksum of the artifact
 */
case class NixArtifact(
  repoName: String,
  relativePath: String,
  url: String,
  sha256: String
) {
  private val entryName = {
    val cleanPath = if (relativePath.startsWith("/")) relativePath.drop(1) else relativePath
    s"$repoName/$cleanPath"
  }

  /**
   * Converts this artifact to a Nix expression.
   */
  def toNix: String = {
    s"""    "${entryName}" = {
       |      url = "${url}";
       |      sha256 = "${sha256.toUpperCase}";
       |    };""".stripMargin
  }
}

object NixArtifact {
  /**
   * Calculate the SHA256 checksum of a file.
   * 
   * @param file The file to calculate the checksum for
   * @return The SHA256 checksum as a hexadecimal string
   */
  def checksum(file: File): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val is = new FileInputStream(file)
    try {
      val dis = new DigestInputStream(is, md)
      val buffer = new Array[Byte](8192)
      while (dis.read(buffer) != -1) {}
      dis.close()
      val digest = md.digest()
      digest.map("%02x".format(_)).mkString
    } finally {
      is.close()
    }
  }
  
  /**
   * Create a NixArtifact from a URL and file.
   * 
   * @param url The URL where the artifact can be downloaded from
   * @param file The local file to calculate the checksum for
   * @return A new NixArtifact
   */
  def fromFile(url: String, file: File): NixArtifact = {
    val artifactId = url.split("/").takeRight(1).head
    NixArtifact("nix-public", artifactId, url, checksum(file))
  }
} 