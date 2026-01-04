package se.nullable.sbtix

import sbt._
import sbt.Keys._
import coursier.core.{Module, Organization, ModuleName}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/**
  * Plugin for generating Nix expressions from SBT project
  */
object NixPlugin extends AutoPlugin {
  
  object autoImport {
    val nixRepoFile = settingKey[File]("nixRepoFile")
    val nixProjectRepo = settingKey[File]("nixProjectRepo")
    val genNixProject = taskKey[Unit]("genNixProject")
    val sbtixBuildInputs = taskKey[BuildInputs]("Return the build inputs computed by sbtix")
    val sbtixNixFile = settingKey[File]("The file where sbtix will write the nix expression")
    val sbtixVersion = settingKey[String]("The version of sbtix used")
  }

  import autoImport._

  override def requires = sbt.plugins.JvmPlugin

  override def projectSettings = Seq(
    sbtixNixFile := new File(baseDirectory.value, "sbtix.nix"),
    nixRepoFile := new File(baseDirectory.value, "repo.nix"),
    nixProjectRepo := new File(new File(baseDirectory.value, "project"), "repo.nix"),
    sbtixVersion := "0.3-SNAPSHOT"
  )

  override def globalSettings = Seq(
    onLoad := {
      val old = onLoad.value
      // Append our task
      taskDefinition
      // Run the old first
      old
    }
  )

  def taskDefinition = { state: State =>
    val extracted = Project.extract(state)

    // Generate the repo file for normal dependencies
    val task = genNixProject := {
      // Find managed dependencies (from Maven or Ivy repositories)
        val managedDependencies = Classpaths
        .managedJars(Compile, classpathTypes.value, update.value)
        .flatMap { 
            case f: Attributed[File] => f.get(Keys.moduleID.key).map(Dependency(_))
        }
        .toSet
        SbtixDebug.info(state.log) {
          s"[SBTIX_DEBUG] Managed deps for genNixProject: ${managedDependencies.size}"
        }
      val scalaVer = scalaVersion.value
      val sbtVer = sbtVersion.value

          val fetcher = new CoursierArtifactFetcher(
            state.log,
            (Compile / externalResolvers).value.toSet,
            (Compile / credentials).value.toSet,
            scalaVersion.value,
            scalaBinaryVersion.value
          )

      val (repos, artifacts, _, _) = fetcher(managedDependencies)

      // Write to file
      val repoFile = nixRepoFile.value
      IO.write(repoFile, buildRepoNix(repos, artifacts, scalaVer, sbtVer))
      
      state.log.info(s"Wrote repository definitions to ${repoFile}")
    }

    // Add the task to this build
    Project.runTask(genNixProject, state)
    state
  }

  def buildRepoNix(
    repos: Set[NixRepo],
    artifacts: Set[NixArtifact],
    scalaVersion: String,
    sbtVersion: String
  ): String =
    NixWriter2(repos, artifacts, scalaVersion, sbtVersion)
}
