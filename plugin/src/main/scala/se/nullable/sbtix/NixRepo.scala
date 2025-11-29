package se.nullable.sbtix

/**
 * Represents a repository in Nix format.
 * 
 * @param name The name of the repository
 * @param url The URL of the repository
 * @param repoType The type of repository (remote, local, etc.)
 */
case class NixRepo(name: String, pattern: String = "") {

  /**
   * Converts this repository to a Nix expression.
   */
  def toNix: String = {
    s"""  "${name}" = "${pattern}";"""
  }
} 