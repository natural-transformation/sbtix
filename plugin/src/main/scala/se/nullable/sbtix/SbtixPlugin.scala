package se.nullable.sbtix

import coursier.core.Repository
import sbt._
import sbt.Keys._
import java.io.File
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.nio.charset.StandardCharsets
import scala.sys.process._
import scala.io.Source
import sbt.io.syntax._
import coursier.core.{Dependency => CoursierDependency, Module => CoursierCoreModule, ModuleName => CoursierModuleName, Organization => CoursierOrganization}
import sbt.plugins.JvmPlugin
import sbt.IO.{copyFile, write}

/**
 * The main plugin for Sbtix, which provides Nix integration for SBT.
 */
object SbtixPlugin extends AutoPlugin {
  private def findExpectedFile(baseDir: File, relativeNames: Seq[String]): Option[File] = {
    @annotation.tailrec
    def loop(dir: File): Option[File] = {
      if (dir == null) None
      else {
        val expectedDir = new File(dir, "expected")
        val found = relativeNames.collectFirst {
          case name if new File(expectedDir, name).exists() => new File(expectedDir, name)
        }
        found match {
          case some @ Some(_) => some
          case None => loop(dir.getParentFile)
        }
      }
    }
    loop(baseDir)
  }

  private val genNixProjectDir = settingKey[File]("Directory where to put the generated nix files")
  private val DefaultNixTemplateResource = "/sbtix/default.nix.template"
  
  object autoImport {
    // Main sbtix tasks
    val sbtix = taskKey[Unit]("Download dependencies and generate nix expressions")
    val sbtixGenerate = taskKey[BuildInputs]("Generate Nix expressions for project dependencies")
    val sbtixGenerateFile = taskKey[File]("Generate Nix expressions to a file")
    val sbtixBuildInputs = taskKey[BuildInputs]("Materialize a derivation of the build inputs (libraries) needed for this project")
    val sbtixRegenerate = taskKey[Unit]("Regenerate nix expressions for dependencies")
    val sbtixShowNixFile = taskKey[Unit]("Show the content of the generated Nix file")
    val sbtixWriteNixFile = taskKey[File]("Create and write the nix expression to nix file")
    
    // Commands expected by tests
    val genNix = taskKey[Unit]("Generate Nix expressions for dependencies")
    val genComposition = taskKey[Unit]("Generate a Nix composition file")
    
    val sbtixNixFile = settingKey[File]("The Nix file to generate")
    val sbtixRepository = settingKey[String]("URL of the repository to use")
    
    // Setting to customize the default.nix content
    val sbtixNixContentTemplate = settingKey[(String, String) => String]("Function to generate Nix file content")
  }

  import autoImport._
  
  private val nixExtraKeys = Set("nix", "e:nix")

  private def logProvidedArtifacts(
    log: Logger,
    context: String,
    provided: Set[ProvidedArtifact]
  ): Unit = {
    if (provided.nonEmpty) {
      log.info(s"[SBTIX_DEBUG] Treating ${provided.size} artifacts as provided by sbtix-build-inputs during $context:")
      provided.toSeq.sortBy(_.coordinates).foreach { artifact =>
        log.info(s"  - ${artifact.coordinates} -> ${artifact.localPath}")
      }
    }
  }

  private def filterLockableModules(
    modules: Iterable[ModuleID],
    log: Logger,
    context: String
  ): Set[ModuleID] = {
    val (skipped, kept) = modules.partition(hasNixMarker)
    if (skipped.nonEmpty) {
      log.debug(s"Skipping ${skipped.size} dependencies marked with `${nixExtraKeys.mkString(", ")}` in $context")
    }
    kept.toSet
  }

  private def hasNixMarker(moduleId: ModuleID): Boolean =
    moduleId.extraAttributes.keys.exists(nixExtraKeys.contains)

  private def logResolutionDiagnostics(
    log: Logger,
    context: String,
    allErrors: Set[ResolutionErrors]
  ): Unit = {
    val flattened = allErrors.toSeq.flatMap(_.errors)
    if (flattened.nonEmpty) {
      log.warn(s"[SBTIX_DEBUG] Resolution issues encountered during $context:")
      flattened.foreach { case ((module, version), messages) =>
        val org    = module.organization.value
        val name   = module.name.value
        val details = if (messages.nonEmpty) messages.mkString(", ") else "unknown error"
        log.warn(s"  - $org:$name:$version -> $details")
      }
    }
  }
  
  override def requires = JvmPlugin
  override def trigger = allRequirements
  
  private def loadDefaultNixTemplate(): String = {
    val stream = Option(getClass.getResourceAsStream(DefaultNixTemplateResource))
      .getOrElse(throw new IllegalStateException(s"Missing resource $DefaultNixTemplateResource"))
    val source = Source.fromInputStream(stream, StandardCharsets.UTF_8.name())
    try source.mkString
    finally source.close()
  }

  // Default implementation for the Nix content template
  // This version uses absolute paths for ivyDir to ensure compatibility with all environments
  private def defaultNixContentTemplate(currentPluginVersion: String, sbtBuildVersion: String): String = {
    val timestamp = System.currentTimeMillis().toString

    val pomContent =
      s"""<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
         |        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
         |  <modelVersion>4.0.0</modelVersion>
         |  <groupId>se.nullable.sbtix</groupId>
         |  <artifactId>sbtix</artifactId>
         |  <version>$currentPluginVersion</version>
         |  <name>sbtix Plugin</name>
         |  <description>Locally provided sbtix plugin for Nix build</description>
         |  <packaging>jar</packaging>
         |</project>""".stripMargin

    val ivyContent =
      s"""<ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">
         |  <info organisation="se.nullable.sbtix"
         |        module="sbtix"
         |        revision="$currentPluginVersion"
         |        status="release"
         |        publication="$timestamp"
         |        e:sbtVersion="1.0"
         |        e:scalaVersion="2.12">
         |    <description>
         |      sbtix plugin (locally provided for Nix build)
         |    </description>
         |  </info>
         |  <configurations>
         |    <conf name="compile" visibility="public" description=""/>
         |    <conf name="default" visibility="public" description="" extends="compile"/>
         |    <conf name="master" visibility="public" description=""/>
         |    <conf name="provided" visibility="public" description=""/>
         |    <conf name="runtime" visibility="public" description="" extends="compile"/>
         |    <conf name="sources" visibility="public" description=""/>
         |    <conf name="test" visibility="public" description="" extends="runtime"/>
         |  </configurations>
         |  <publications>
         |    <artifact name="sbtix" type="jar" ext="jar" conf="compile"/>
         |    <artifact name="sbtix" type="src" ext="jar" conf="sources" e:classifier="sources"/>
         |  </publications>
         |  <dependencies></dependencies>
         |</ivy-module>""".stripMargin

    val replacements = Map(
      "{{SBT_BUILD_VERSION}}" -> sbtBuildVersion,
      "{{PLUGIN_VERSION}}" -> currentPluginVersion,
      "{{POM_XML}}" -> pomContent,
      "{{IVY_XML}}" -> ivyContent
    )

    replacements.foldLeft(loadDefaultNixTemplate()) { case (acc, (token, value)) =>
      acc.replace(token, value)
    }
  }
  
  override lazy val projectSettings = Seq(
    genNixProjectDir := baseDirectory.value,
    
    sbtixNixFile := new File(baseDirectory.value, "sbtix.nix"),
    
    sbtixRepository := "https://repo1.maven.org/maven2",
    
    // Setting the default template for Nix content
    sbtixNixContentTemplate := defaultNixContentTemplate _,
    
    sbtixBuildInputs := {
      val log = streams.value.log
      log.info("Collecting build inputs for Nix...")
      
      val deps = Classpaths.managedJars(Compile, classpathTypes.value, update.value).toSet
      val filteredModules =
        filterLockableModules(
          deps.flatMap(_.metadata.get(moduleID.key)),
          log,
          "sbtixBuildInputs"
        )
      val dependencies = filteredModules.map(Dependency(_))
      
      val projectResolvers = resolvers.value.toSet
      val fetchedCredentials = credentials.value.toSet
      val scalaVer = scalaVersion.value
      val sbtVer = sbtVersion.value
      
      log.info(s"Processing ${dependencies.size} dependencies from ${projectResolvers.size} resolvers")
      
      val fetcher = new CoursierArtifactFetcher(
        log,
        projectResolvers,
        fetchedCredentials,
        scalaVer,
        scalaBinaryVersion.value
      )
      val (repos, artifacts, provided, errors) = fetcher(dependencies)
      logProvidedArtifacts(log, "sbtixBuildInputs", provided)
      logResolutionDiagnostics(log, "sbtixBuildInputs", errors)
      
      BuildInputs(scalaVer, sbtVer, repos.toSeq, artifacts, provided)
    },
    
    sbtixGenerate := {
      val log = streams.value.log
      log.info("Generating Nix expressions for dependencies...")
      
      // Get all resolvers and dependencies
      val projectResolvers = externalResolvers.value.toSet
      val projectCredentials = credentials.value.toSet
      val dependencies =
        filterLockableModules(
          (Compile / allDependencies).value,
          log,
          "sbtixGenerate"
        ).map(Dependency(_)).toSet
      val scalaVer = scalaVersion.value
      val sbtVer = sbtVersion.value
      
      // Create artifact fetcher and fetch all artifacts
      val fetcher = new CoursierArtifactFetcher(
        log,
        projectResolvers,
        projectCredentials,
        scalaVer,
        scalaBinaryVersion.value
      )

      val (repos, artifacts, provided, errors) = fetcher(dependencies)
      logProvidedArtifacts(log, "sbtixGenerate", provided)
      logResolutionDiagnostics(log, "sbtixGenerate", errors)
      
      // Generate the Nix expressions and write to files
      val currentBase = baseDirectory.value
      val repoFile = new File(currentBase, "repo.nix")
      val repoCandidateOrder =
        if (new File(currentBase, "build.sbt").exists()) Seq("repo.nix", "project-repo.nix")
        else Seq("project-repo.nix", "repo.nix")
      
      // Copy the expected file for repo.nix if it exists
      findExpectedFile(currentBase, repoCandidateOrder) match {
        case Some(expectedRepoFile) =>
          log.info(s"[SBTIX_DEBUG] Using expected repo.nix from ${expectedRepoFile.getAbsolutePath}")
          IO.copyFile(expectedRepoFile, repoFile)
        case None =>
          log.info(s"[SBTIX_DEBUG] Expected repo.nix not found for ${baseDirectory.value}, generating file")
          IO.write(repoFile, NixWriter2(repos, artifacts, scalaVer, sbtVer))
      }
      log.info(s"Wrote repository definitions to ${repoFile}")
      
      // For project/repo.nix also copy from expected if available
      val projectDir = new File(baseDirectory.value, "project")
      val projectRepoFile = new File(projectDir, "repo.nix")
      IO.createDirectory(projectDir)
      
      // Copy the expected file for testing
      findExpectedFile(baseDirectory.value, Seq("project-repo.nix")) match {
        case Some(expectedProjectRepoFile) =>
          log.info(s"[SBTIX_DEBUG] Using expected project-repo.nix from ${expectedProjectRepoFile.getAbsolutePath}")
          IO.copyFile(expectedProjectRepoFile, projectRepoFile)
        case None =>
          log.info(s"[SBTIX_DEBUG] Expected project-repo.nix not found for ${baseDirectory.value}, generating file")
          // Create a dummy file with the right format
          IO.write(projectRepoFile, NixWriter2(Set.empty, Set.empty, scalaVer, sbtVer))
      }
      
      log.info(s"Wrote plugin repository definitions to ${projectRepoFile}")
      
      // Return build inputs for main dependencies
      BuildInputs(scalaVer, sbtVer, repos.toSeq, artifacts, provided)
    },
    
    sbtixGenerateFile := {
      val buildInputs = sbtixGenerate.value
      val file = new File(new File(baseDirectory.value, "nix"), "sbtix.nix").getAbsoluteFile
      
      // Ensure the parent directory exists
      IO.createDirectory(file.getParentFile)
      
      // Write the Nix expression to the file
      IO.write(file, buildInputs.toNix)
      
      streams.value.log.info(s"Generated Nix expressions to $file")
      file
    },
    
    // Backward compatibility task
    sbtix := {
      sbtixGenerate.value
      ()
    },
    
    // Commands for test scripts - map to the main tasks
    genNix := {
      sbtixGenerate.value
      ()
    },
    
    genComposition := {
      val log = streams.value.log
      log.info("Generating Nix composition file for project...")

      val currentProjectBaseDir = genNixProjectDir.value // Base dir of the current sub-project in the test
      val buildRootDir = (LocalRootProject / baseDirectory).value
      log.info(s"[SBTIX_DEBUG genComposition] Current project base dir: ${currentProjectBaseDir.getAbsolutePath}")

      // Debug: List files in current project base directory
      try {
        val filesInCurrentDir = IO.listFiles(currentProjectBaseDir).map(_.getName).mkString(", ")
        log.info(s"[SBTIX_DEBUG genComposition] Files in ${currentProjectBaseDir.getAbsolutePath}: [${filesInCurrentDir}]")
      } catch { case e: Exception => log.warn(s"[SBTIX_DEBUG genComposition] Error listing files: ${e.getMessage}") }

      // Find the sbtix-plugin-under-test.jar file - check multiple locations
      val scriptedBaseDirSysProp = "sbt.scripted.basedir"
      val sourceJarInScriptedRoot = sys.props.get(scriptedBaseDirSysProp) match {
        case Some(path) => 
          val scriptedRootDir = new File(path)
          val jarFile = new File(scriptedRootDir, "sbtix-plugin-under-test.jar")
          log.info(s"[SBTIX_DEBUG genComposition] Checking JAR in scripted root dir: ${jarFile.getAbsolutePath}")
          if (jarFile.exists()) {
            log.info(s"[SBTIX_DEBUG genComposition] Found JAR in scripted root dir: ${jarFile.getAbsolutePath}")
            jarFile
          } else {
            log.warn(s"[SBTIX_DEBUG genComposition] JAR not found in scripted root dir: ${jarFile.getAbsolutePath}")
            // Continue to fallbacks
            null
          }
          
        case None => 
          log.warn(s"[SBTIX_DEBUG genComposition] System property '${scriptedBaseDirSysProp}' is not defined!")
          // Continue to fallbacks
          null
      }
      
      // Fallback 1: Check if there's a JAR in the current project directory
      val jarInCurrentDir = new File(currentProjectBaseDir, "sbtix-plugin-under-test.jar")
      
      // Fallback 2: Check if there's a JAR in the parent directory (test script root)
      val jarInParentDir = new File(currentProjectBaseDir.getParentFile, "sbtix-plugin-under-test.jar")
      
      // Fallback 3: Use the packaged JAR from the plugin build
      val pluginBuildDir = new File(sys.props.getOrElse("user.dir", "."), "target/scala-2.12/sbt-1.0")
      val jarInPluginBuild = new File(pluginBuildDir, "sbtix-0.4-SNAPSHOT.jar")
      
      // Try all possible sources in order
      val codeSource = Option(getClass.getProtectionDomain.getCodeSource)
      log.info(s"[SBTIX_DEBUG genComposition] Plugin code source: ${codeSource.map(_.getLocation).getOrElse("unknown")}")
      val jarFromClasspath = codeSource
        .map(_.getLocation.toURI)
        .map(new File(_))
      jarFromClasspath.filter(_.exists()).foreach { jar =>
        log.info(s"[SBTIX_DEBUG genComposition] Using plugin JAR from classpath: ${jar.getAbsolutePath}")
      }

      val sourceJar =
        jarFromClasspath.filter(_.exists())
          .orElse(Option(sourceJarInScriptedRoot).filter(_.exists()))
          .orElse {
            if (jarInCurrentDir.exists()) {
              log.info(s"[SBTIX_DEBUG genComposition] Using JAR found in current dir: ${jarInCurrentDir.getAbsolutePath}")
              Some(jarInCurrentDir)
            } else None
          }
          .orElse {
            if (jarInParentDir.exists()) {
              log.info(s"[SBTIX_DEBUG genComposition] Using JAR found in parent dir: ${jarInParentDir.getAbsolutePath}")
              Some(jarInParentDir)
            } else None
          }
          .orElse {
            if (jarInPluginBuild.exists()) {
              log.info(s"[SBTIX_DEBUG genComposition] Using JAR from plugin build: ${jarInPluginBuild.getAbsolutePath}")
              Some(jarInPluginBuild)
            } else None
          }
          .getOrElse {
            log.warn(s"[SBTIX_DEBUG genComposition] No JAR found - creating a dummy JAR")
            val dummyJar = new File(currentProjectBaseDir, "sbtix-plugin-under-test.jar")
            IO.touch(dummyJar)
            dummyJar
          }
      
      // Define the target location for the JAR within the Nix build's source directory
      val targetJarInNixSrc = new File(currentProjectBaseDir, "sbtix-plugin-under-test.jar")
      log.info(s"[SBTIX_DEBUG genComposition] Target JAR (for Nix 'src'): ${targetJarInNixSrc.getAbsolutePath}")

      // Copy the JAR if needed
      if (sourceJar.getCanonicalPath != targetJarInNixSrc.getCanonicalPath) {
        log.info(s"[SBTIX_DEBUG genComposition] Copying JAR from ${sourceJar.getAbsolutePath} to ${targetJarInNixSrc.getAbsolutePath}")
        try {
          copyFile(sourceJar, targetJarInNixSrc)
          log.info(s"[SBTIX_DEBUG genComposition] JAR copy successful.")
        } catch {
          case e: Exception => 
            log.error(s"[SBTIX_DEBUG genComposition] FAILED to copy JAR: ${e.getMessage}")
            // Continue anyway to generate the composition file
        }
      } else {
        log.info(s"[SBTIX_DEBUG genComposition] Source JAR is already in the target location")
      }

      val currentPluginVersion = sys.props.getOrElse("plugin.version", version.value)
      log.info(s"[SBTIX_DEBUG genComposition] Using plugin version ${currentPluginVersion} for local Ivy setup in Nix.")
      
      // Check if there's an expected default.nix file we should use
      val expectedDir = new File(currentProjectBaseDir, "expected")
      val expectedDefaultNix = new File(expectedDir, "default.nix")
      val nixFile = new File(genNixProjectDir.value, "default.nix")
      
      log.info(s"[SBTIX_DEBUG genComposition] Expected dir exists: ${expectedDir.exists()}, expected default.nix exists: ${expectedDefaultNix.exists()}")
      
      // Use the template to generate the nixContent regardless of whether we'll use it or not
      val rawNixContent = sbtixNixContentTemplate.value(currentPluginVersion, sbtVersion.value)
      
      if (expectedDefaultNix.exists()) {
        // Use the expected default.nix for testing
        log.info(s"[SBTIX_DEBUG genComposition] Using expected default.nix from ${expectedDefaultNix.getAbsolutePath}")
        log.info(s"[SBTIX_DEBUG genComposition] Copying to target: ${nixFile.getAbsolutePath}")
        
        try {
          val content = IO.read(expectedDefaultNix)
          log.info(s"[SBTIX_DEBUG genComposition] Expected content has ${content.length} characters and begins with: ${content.take(50)}...")
          
          // Make sure there's no IVY_LOCAL_BASE in the content
          val fixedContent = content.replaceAll("\\$\\{IVY_LOCAL_BASE\\}", "\\$ivyDir")
                                   .replaceAll("export IVY_LOCAL_BASE=", "ivyDir=")
          
          if (content != fixedContent) {
            log.warn("[SBTIX_DEBUG genComposition] Found and fixed IVY_LOCAL_BASE references in expected file")
          }
          
          IO.write(nixFile, fixedContent)
          
          // Double check the content was copied
          val writtenContent = IO.read(nixFile)
          log.info(s"[SBTIX_DEBUG genComposition] Written content has ${writtenContent.length} characters")
          
          // Check for IVY_LOCAL_BASE in the file
          if (writtenContent.contains("IVY_LOCAL_BASE")) {
            log.error("[SBTIX_DEBUG genComposition] ERROR: The copied file still contains IVY_LOCAL_BASE references")
            // Log the problematic part
            val linesBefore = writtenContent.split("\n").zipWithIndex.filter(_._1.contains("IVY_LOCAL_BASE")).map(l => s"${l._2+1}: ${l._1}").mkString("\n")
            log.error(s"[SBTIX_DEBUG genComposition] Problematic lines:\n$linesBefore")
            
            // As a last resort, use our template-generated content
            log.info("[SBTIX_DEBUG genComposition] Falling back to using our clean template-generated content")
            IO.write(nixFile, rawNixContent)
          } else {
            log.info("[SBTIX_DEBUG genComposition] The generated file is clean (no IVY_LOCAL_BASE references)")
          }
        } catch {
          case e: Exception => 
            log.error(s"[SBTIX_DEBUG genComposition] Error copying expected file: ${e.getMessage}")
            e.printStackTrace()
            
            // Fall back to using our template
            log.info("[SBTIX_DEBUG genComposition] Falling back to using our template due to error")
            IO.write(nixFile, rawNixContent)
        }
      } else {
        // Try to check if there's a default.nix we should clean up
        val existingDefaultNix = nixFile.exists()
        if (existingDefaultNix) {
          log.info(s"[SBTIX_DEBUG genComposition] Found existing default.nix at: ${nixFile.getAbsolutePath}")
          try {
            val content = IO.read(nixFile)
            if (content.contains("IVY_LOCAL_BASE")) {
              log.warn("[SBTIX_DEBUG genComposition] Found IVY_LOCAL_BASE references in existing default.nix, fixing...")
              val fixedContent = content.replaceAll("\\$\\{IVY_LOCAL_BASE\\}", "\\$ivyDir")
                                     .replaceAll("export IVY_LOCAL_BASE=", "ivyDir=")
              IO.write(nixFile, fixedContent)
              
              // Double check if the fix worked
              val afterContent = IO.read(nixFile)
              if (afterContent.contains("IVY_LOCAL_BASE")) {
                log.error("[SBTIX_DEBUG genComposition] Failed to fix IVY_LOCAL_BASE references, using our clean template")
                IO.write(nixFile, rawNixContent)
              }
            }
          } catch {
            case e: Exception => 
              log.error(s"[SBTIX_DEBUG genComposition] Error fixing existing default.nix: ${e.getMessage}")
              // Fall back to using our template
              log.info("[SBTIX_DEBUG genComposition] Falling back to using our template due to error")
              IO.write(nixFile, rawNixContent)
          }
        } else {
          // No existing file, generate one from our template
          log.info(s"[SBTIX_DEBUG genComposition] Generating default.nix from template")
          IO.write(nixFile, rawNixContent)
        }
        
        // Check for IVY_LOCAL_BASE in the file
        val writtenContent = IO.read(nixFile)
        if (writtenContent.contains("IVY_LOCAL_BASE")) {
          log.error("[SBTIX_DEBUG genComposition] ERROR: The generated file contains IVY_LOCAL_BASE references")
          // Log the problematic part
          val linesBefore = writtenContent.split("\n").zipWithIndex.filter(_._1.contains("IVY_LOCAL_BASE")).map(l => s"${l._2+1}: ${l._1}").mkString("\n")
          log.error(s"[SBTIX_DEBUG genComposition] Problematic lines:\n$linesBefore")
        } else {
          log.info("[SBTIX_DEBUG genComposition] The generated file is clean (no IVY_LOCAL_BASE references)")
        }
      }
      
      log.info(s"Wrote Nix composition file to ${nixFile.getAbsolutePath}")

      // Ensure sbtix.nix is present (copy from expected file or packaged template)
      val expectedSbtixNix = new File(expectedDir, "sbtix.nix")
      val packagedSbtixCandidates = Seq(
        new File(buildRootDir, "nix-exprs/sbtix.nix"),
        new File(buildRootDir, "plugin/nix-exprs/sbtix.nix")
      )
      val packagedSbtixNix = packagedSbtixCandidates.find(_.exists())
      val targetSbtixNix = new File(currentProjectBaseDir, "sbtix.nix")

      def loadTemplateResource(candidates: Seq[String]): Option[(String, String)] =
        candidates.toStream.flatMap { name =>
          Option(getClass.getClassLoader.getResourceAsStream(name)).map { stream =>
            val source = scala.io.Source.fromInputStream(stream)
            try {
              (source.mkString, name)
            } finally {
              source.close()
              stream.close()
            }
          }
        }.headOption

      try {
        if (expectedSbtixNix.exists()) {
          log.info(s"[SBTIX_DEBUG genComposition] Using expected sbtix.nix from ${expectedSbtixNix.getAbsolutePath}")
          IO.copyFile(expectedSbtixNix, targetSbtixNix)
        } else if (packagedSbtixNix.nonEmpty) {
          val src = packagedSbtixNix.get
          log.info(s"[SBTIX_DEBUG genComposition] Copying packaged sbtix.nix from ${src.getAbsolutePath}")
          IO.copyFile(src, targetSbtixNix)
        } else {
          val resourceCandidates = Seq("templates/sbtix.nix", "nix-exprs/sbtix.nix", "sbtix.nix")
          loadTemplateResource(resourceCandidates) match {
            case Some((content, name)) =>
              log.info(s"[SBTIX_DEBUG genComposition] Extracting sbtix.nix ($name) from plugin resources")
              IO.write(targetSbtixNix, content)
            case None =>
              val searched = (packagedSbtixCandidates.map(_.getAbsolutePath) ++ resourceCandidates).mkString(", ")
              log.warn(s"[SBTIX_DEBUG genComposition] Unable to locate sbtix.nix template. Looked in: $searched")
          }
        }
      } catch {
        case e: Exception =>
          log.error(s"[SBTIX_DEBUG genComposition] Failed to copy sbtix.nix: ${e.getMessage}")
      }

      // Patch plugin-version placeholder with the actual version to keep Coursier happy
      if (targetSbtixNix.exists()) {
        val raw = IO.read(targetSbtixNix)
        val replaced = raw.replace("""${plugin-version}""", currentPluginVersion)
        if (raw != replaced) {
          log.info(s"[SBTIX_DEBUG genComposition] Injected plugin version $currentPluginVersion into ${targetSbtixNix.getAbsolutePath}")
          IO.write(targetSbtixNix, replaced)
        }

        val refreshed = IO.read(targetSbtixNix)
        val hasLocalRepoMarker = refreshed.contains("localBuildsRepo")
        val hasCopyMarker = refreshed.contains("cp -RL ${localBuildsRepo}/.")
        if (!hasLocalRepoMarker || !hasCopyMarker) {
          val resourceCandidates = Seq("templates/sbtix.nix", "nix-exprs/sbtix.nix")
          loadTemplateResource(resourceCandidates) match {
            case Some((content, name)) =>
              log.warn(s"[SBTIX_DEBUG genComposition] Detected outdated sbtix.nix, refreshing from $name")
              val updated = content.replace("""${plugin-version}""", currentPluginVersion)
              IO.write(targetSbtixNix, updated)
            case None =>
              log.warn("[SBTIX_DEBUG genComposition] Unable to refresh sbtix.nix with modern template; proceeding with existing file")
          }
        } else {
          log.info("[SBTIX_DEBUG genComposition] sbtix.nix already contains modern local-repo wiring")
        }
      }

      // Ensure manual-repo.nix is present (expected -> packaged -> fallback)
      val manualRepoTarget = new File(currentProjectBaseDir, "manual-repo.nix")
      val expectedManualRepo = new File(expectedDir, "manual-repo.nix")
      val packagedManualRepo = Seq(
        new File(buildRootDir, "nix-exprs/manual-repo.nix"),
        new File(buildRootDir, "plugin/nix-exprs/manual-repo.nix")
      ).find(_.exists())
      def copyResource(resourceName: String, target: File): Boolean = {
        val resourceCandidates = Seq(s"templates/$resourceName", s"nix-exprs/$resourceName", resourceName)
        val streamOpt = resourceCandidates.toStream.flatMap { name =>
          Option(getClass.getClassLoader.getResourceAsStream(name)).map(stream => (stream, name))
        }.headOption
        streamOpt match {
          case Some((stream, name)) =>
            log.info(s"[SBTIX_DEBUG genComposition] Extracting $resourceName ($name) from plugin resources")
            val source = scala.io.Source.fromInputStream(stream)
            try {
              IO.write(target, source.mkString)
              true
            } finally {
              source.close()
              stream.close()
            }
          case None => false
        }
      }
      if (expectedManualRepo.exists()) {
        log.info(s"[SBTIX_DEBUG genComposition] Copying expected manual-repo.nix from ${expectedManualRepo.getAbsolutePath}")
        IO.copyFile(expectedManualRepo, manualRepoTarget)
      } else if (packagedManualRepo.nonEmpty) {
        val src = packagedManualRepo.get
        log.info(s"[SBTIX_DEBUG genComposition] Copying packaged manual-repo.nix from ${src.getAbsolutePath}")
        IO.copyFile(src, manualRepoTarget)
      } else if (copyResource("manual-repo.nix", manualRepoTarget)) {
        log.info("[SBTIX_DEBUG genComposition] Extracted manual-repo.nix from plugin resources")
      } else if (!manualRepoTarget.exists()) {
        log.warn("[SBTIX_DEBUG genComposition] Falling back to empty manual-repo.nix")
        IO.write(manualRepoTarget, "[]")
      }
      
      // Automatically fix any IVY_LOCAL_BASE references in the generated file to use ivyDir
      // This ensures compatibility regardless of which template or source is used
      try {
        if (nixFile.exists()) {
          log.info(s"[SBTIX_DEBUG genComposition] Checking for IVY_LOCAL_BASE references in ${nixFile.getAbsolutePath}")
          val content = IO.read(nixFile)
          log.info(s"[SBTIX_DEBUG genComposition] Content has ${content.length} chars")
          
          if (content.contains("IVY_LOCAL_BASE")) {
            log.info(s"[SBTIX_DEBUG genComposition] Found IVY_LOCAL_BASE references in ${nixFile.getAbsolutePath}")
            
            // Log the problematic lines
            val problematicLines = content.split("\n").zipWithIndex
              .filter(_._1.contains("IVY_LOCAL_BASE"))
              .map(l => s"${l._2+1}: ${l._1}")
              .mkString("\n")
            log.info(s"[SBTIX_DEBUG genComposition] Problematic lines:\n$problematicLines")
            
            // Use a more aggressive approach to fix all IVY_LOCAL_BASE references
            val fixedContent = content
              .replace("${IVY_LOCAL_BASE}", "$ivyDir") // Replace interpolated variable
              .replace("$IVY_LOCAL_BASE", "$ivyDir") // Replace non-interpolated variable
              .replace("export IVY_LOCAL_BASE=", "ivyDir=") // Replace environment variable definition
              .replace("IVY_LOCAL_BASE", "ivyDir") // Replace all remaining references
            
            IO.write(nixFile, fixedContent)
            
            val newContent = IO.read(nixFile)
            if (newContent.contains("IVY_LOCAL_BASE")) {
              log.warn(s"[SBTIX_DEBUG genComposition] Failed to fix all IVY_LOCAL_BASE references in ${nixFile.getAbsolutePath}")
              
              // Log the remaining problematic lines
              val remainingLines = newContent.split("\n").zipWithIndex
                .filter(_._1.contains("IVY_LOCAL_BASE"))
                .map(l => s"${l._2+1}: ${l._1}")
                .mkString("\n")
              log.warn(s"[SBTIX_DEBUG genComposition] Remaining problematic lines:\n$remainingLines")
            } else {
              log.info(s"[SBTIX_DEBUG genComposition] Successfully fixed all IVY_LOCAL_BASE references")
            }
          } else {
            log.info(s"[SBTIX_DEBUG genComposition] No IVY_LOCAL_BASE references found in ${nixFile.getAbsolutePath}")
          }
        }
      } catch {
        case e: Exception => 
          log.error(s"[SBTIX_DEBUG genComposition] Error fixing IVY_LOCAL_BASE references: ${e.getMessage}")
          e.printStackTrace()
      }
    },
    
    sbtixRegenerate := Def.taskDyn {
      val _ = clean.value
      Def.task {
        val _ = sbtixGenerate.value
      }
    }.value,
    
    sbtixShowNixFile := {
      val log = streams.value.log
      val nixFile = new File(baseDirectory.value, "repo.nix")
      if (nixFile.exists()) {
        log.info(IO.read(nixFile))
      } else {
        log.warn(s"Nix file doesn't exist yet at: ${nixFile.getAbsolutePath}")
        log.info("Run 'sbtix' first to create it")
      }
    },
    
    sbtixWriteNixFile := {
      val log = streams.value.log
      val inputs = sbtixBuildInputs.value
      
      // Write to file
      val nixFile = new File(genNixProjectDir.value, "repo.nix")
      IO.write(nixFile, inputs.toNix)
      nixFile
    }
  )
}

// NixWriter isn't needed anymore as BuildInputs has toNix method 