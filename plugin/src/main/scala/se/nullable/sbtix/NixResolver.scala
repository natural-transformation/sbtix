package se.nullable.sbtix

import sbt._

/**
 * Resolves repositories to Nix expressions
 */
trait NixResolver {
  def nixRepo: NixRepo
}

/**
 * Simple implementation of NixResolver
 */
case class SimpleNixResolver(name: String, repo: NixRepo) extends NixResolver {
  override def nixRepo: NixRepo = repo
}

/**
 * Contains utility methods for creating NixResolvers
 */
object NixResolver {
  /**
   * Converts an SBT Resolver to a NixResolver
   */
  def fromSbtResolver(resolver: Resolver): NixResolver = {
    resolver match {
      case repo: MavenRepository =>
        val name = cleanName(repo.name)
        SimpleNixResolver(name, NixRepo(name, repo.root))
      case _ =>
        val name = cleanName(resolver.name)
        SimpleNixResolver(name, NixRepo(name))
    }
  }
  
  /**
   * Clean a repository name for use in Nix
   */
  def cleanName(name: String): String = {
    name.replaceAll("[^a-zA-Z0-9._-]", "_")
  }
}
