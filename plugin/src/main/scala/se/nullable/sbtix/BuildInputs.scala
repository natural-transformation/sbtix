package se.nullable.sbtix

import java.io.{File, PrintWriter}

/**
 * Contains all the build inputs (repositories and artifacts) required for a build.
 *
 * @param scalaVersion The Scala version used by the project
 * @param sbtVersion The sbt version used by the project
 * @param nixRepositories The Nix repositories to include
 * @param nixArtifacts The Nix artifacts to include
 */
case class BuildInputs(
  scalaVersion: String,
  sbtVersion: String,
  nixRepositories: Seq[NixRepo],
  nixArtifacts: Set[NixArtifact]
) {

  /**
   * Converts this BuildInputs to a Nix expression.
   */
  def toNix: String =
    NixWriter2(nixRepositories.toSet, nixArtifacts, scalaVersion, sbtVersion)

  /**
   * Write build inputs to a file.
   */
  def writeTo(file: File): Unit = {
    val writer = new PrintWriter(file)
    try writer.write(toNix)
    finally writer.close()
  }
}
