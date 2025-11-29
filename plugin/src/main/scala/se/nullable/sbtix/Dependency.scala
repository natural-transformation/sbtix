package se.nullable.sbtix

import sbt.ModuleID

/**
 * Represents a dependency in SBT.
 * 
 * @param moduleId The module ID of the dependency
 */
case class Dependency(moduleId: ModuleID) {
  /** 
   * Get the dependency path in Maven-like format
   */
  def mavenPath: String = {
    s"${moduleId.organization.replace('.', '/')}/${moduleId.name}/${moduleId.revision}"
  }
  
  /**
   * Get the artifact ID for this dependency
   */
  def artifactId: String = {
    s"${moduleId.organization}:${moduleId.name}:${moduleId.revision}"
  }
}

object Dependency {
  /**
   * Creates a Dependency from a ModuleID
   */
  def apply(moduleId: ModuleID): Dependency = new Dependency(moduleId)
} 