package se.nullable.sbtix

import coursier.core.Repository
import sbt._
import sbt.Keys._
import sbt.librarymanagement.{ CrossVersion, Patterns }
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
  import se.nullable.sbtix.SbtixHashes._
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
  private val SampleDefaultTemplateResource = "/sbtix/default.nix.template"
  private val GeneratedNixTemplateResource = "/sbtix/generated.nix.template"
  private val StoreBootstrapMarker = "sbtixSourceFetcher = {"
  private val GeneratedNixFileName = "sbtix-generated.nix"
  // Keep generated Nix deterministic.
  //
  // The value below is only used as the Ivy `<info publication="...">` string
  // in the bootstrap metadata we synthesize during Nix builds. It does not
  // affect resolution, but it *does* affect diffs when regenerating nix files.
  private val DefaultPublicationTimestamp = "0000000000000"
  
  private def indentMultiline(text: String, indent: String): String =
    text.linesIterator.map(line => indent + line).mkString("\n")

  /** Builds the Maven POM fragment that `Coursier` expects for the locally staged
    * plugin jar. We emit it directly instead of calling `publishLocal` because the
    * Nix derivation runs before any sbt process is available inside the sandbox.
    */
  private def pluginPomXml(version: String): String =
    s"""<project xmlns="http://maven.apache.org/POM/4.0.0"
       |         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       |         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
       |  <modelVersion>4.0.0</modelVersion>
       |  <groupId>se.nullable.sbtix</groupId>
       |  <artifactId>sbtix</artifactId>
       |  <version>$version</version>
       |  <name>sbtix Plugin</name>
       |  <description>Locally provided sbtix plugin for Nix build</description>
       |  <packaging>jar</packaging>
       |</project>""".stripMargin

  /** Emits the companion Ivy metadata used by the sbt launcher during bootstrap.
    * The `publicationTimestamp` keeps the Ivy entry stable but deterministic, and
    * we hard-code the SBT/Scala versions so that template updates happen together
    * with the plugin build definition.
    */
  private def pluginIvyXml(version: String, publicationTimestamp: String): String =
    s"""<ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">
       |  <info organisation="se.nullable.sbtix"
       |        module="sbtix"
       |        revision="$version"
       |        status="release"
       |        publication="$publicationTimestamp"
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
       |  </publications>
       |  <dependencies></dependencies>
       |</ivy-module>""".stripMargin

  /** Shell snippet that runs inside the Nix build sandbox. It synthesizes a
    * minimal Ivy repository entry under `.ivy2-home` so that Coursier can resolve
    * the plugin JAR from the files we already staged in the derivation.
    */
  private case class SourceBlock(block: String, pluginJarLine: String)

  // The plugin version we should bake into generated Nix. We must not rely on
  // `version.value`, because that would pick up the user's *project* version.
  //
  // Prefer the wrapper-provided plugin jar path (`SBTIX_PLUGIN_JAR_PATH` /
  // `sbtix.pluginJarPath`): it encodes the version in the Ivy layout we publish
  // to. If it is missing, fall back to the jar manifest version (when present).
  //
  // Important: do not hardcode a plugin version here; that breaks long-term
  // reproducibility and caused stale fixtures in CI.
  private def pluginVersionFromPluginJarPath(path: String): Option[String] = {
    val cleaned = path.stripPrefix("file:")
    val jarFile = new File(cleaned)
    Option(jarFile.getParentFile)
      .flatMap(parent => Option(parent.getParentFile))
      .map(_.getName)
      .filter(_.nonEmpty)
  }

  private def resolveSbtixSourceBlock(pluginVersion: String): SourceBlock = {
    def fromEnvOrProp(envKey: String, propKey: String): Option[String] =
      sys.props.get(propKey).filter(_.nonEmpty).orElse(sys.env.get(envKey))

    def nixPathExpr(path: String): String = {
      // When falling back to a local/store-backed source, prefer emitting a Nix
      // path literal when possible. This is simple and works well for `nix-build`
      // style flows and CI source archives.
      //
      // NOTE: Pure flake evaluation forbids absolute path literals, so this
      // helper must never be used when we have a proper (rev + narHash) pin.
      if (path.startsWith("/")) path
      else {
        val escaped = path
          .replace("\\", "\\\\")
          .replace("\"", "\\\"")
        s"""builtins.toPath "$escaped""""
      }
    }

    val maybeSourcePath = fromEnvOrProp("SBTIX_SOURCE_PATH", "sbtix.sourcePath")
    val maybeRev = fromEnvOrProp("SBTIX_SOURCE_REV", "sbtix.sourceRev")
    val maybeNarHash = fromEnvOrProp("SBTIX_SOURCE_NAR_HASH", "sbtix.sourceNarHash")
    val sourceUrl = fromEnvOrProp("SBTIX_SOURCE_URL", "sbtix.sourceUrl")
      .getOrElse("https://github.com/natural-transformation/sbtix")

    // Prefer a pinned fetcher when possible.
    //
    // Flake evaluation runs in *pure* mode and forbids accessing absolute paths
    // (including store paths) introduced as path literals. There is also no
    // pure-eval-safe way to "re-hydrate" a store path from a string
    // (`builtins.storePath` is forbidden in pure evaluation mode).
    //
    // Therefore, for flake-friendly generated Nix we must use a pinned fetcher
    // (rev + narHash) whenever it is available.
    (maybeRev, maybeNarHash) match {
      case (Some(rev), Some(narHash)) =>
        val sha256 = sriToNixBase32(narHash).getOrElse {
          throw new IllegalArgumentException(
            s"Unable to convert narHash '$narHash' into a Nix base32 sha256 hash."
          )
        }
        val block =
          s"""  sbtixSourceFetcher = { url, rev, narHash, sha256, ... }@args:
             |    if builtins ? fetchTree
             |    then builtins.fetchTree (builtins.removeAttrs args [ "sha256" ])
             |    else pkgs.fetchgit {
             |      inherit url rev sha256;
             |    };
             |
             |  sbtixSource = sbtixSourceFetcher {
             |    type = "git";
             |    url = "$sourceUrl";
             |    rev = "$rev";
             |    narHash = "$narHash";
             |    sha256 = "$sha256";
             |  };
             |
             |  sbtixPluginRepos = [
             |    (import (sbtixSource + "/plugin/repo.nix"))
             |    (import (sbtixSource + "/plugin/project/repo.nix"))
             |    (import (sbtixSource + "/plugin/nix-exprs/manual-repo.nix"))
             |  ];
             |
             |  sbtixPluginIvy = sbtix.buildSbtLibrary {
             |    name = "sbtix-plugin";
             |    src = cleanSource (sbtixSource + "/plugin");
             |    repo = sbtixPluginRepos;
             |  };
             |
             |  pluginVersion =
             |    let versions = builtins.attrNames (builtins.readDir "${"$"}{sbtixPluginIvy}/se.nullable.sbtix/sbtix/scala_2.12/sbt_1.0");
             |    in builtins.head versions;
             |
             |  sbtixPluginJarPath = "${"$"}{sbtixPluginIvy}/se.nullable.sbtix/sbtix/scala_2.12/sbt_1.0/${"$"}{pluginVersion}/jars/sbtix.jar";
             |""".stripMargin
        SourceBlock(block, "      pluginJar=\"${sbtixPluginJarPath}\"\n")
      case _ =>
        // Fallback: if we don't have a (rev + narHash) pin, try to use a
        // store-backed source path (e.g. when sbtix itself was built from a
        // local checkout or a source archive without VCS metadata).
        //
        // NOTE: This fallback is *not* suitable for pure flake evaluation, but
        // it keeps `nix-build` flows and sbtix's own CI fixtures working.
        maybeSourcePath match {
          case Some(sourcePath) =>
            val block =
              s"""  sbtixSource = ${nixPathExpr(sourcePath)};
                 |
                 |  sbtixPluginRepos = [
                 |    (import (sbtixSource + "/plugin/repo.nix"))
                 |    (import (sbtixSource + "/plugin/project/repo.nix"))
                 |    (import (sbtixSource + "/plugin/nix-exprs/manual-repo.nix"))
                 |  ];
                 |
                 |  sbtixPluginIvy = sbtix.buildSbtLibrary {
                 |    name = "sbtix-plugin";
                 |    src = cleanSource (sbtixSource + "/plugin");
                 |    repo = sbtixPluginRepos;
                 |  };
                 |
                 |  pluginVersion =
                 |    let versions = builtins.attrNames (builtins.readDir "${"$"}{sbtixPluginIvy}/se.nullable.sbtix/sbtix/scala_2.12/sbt_1.0");
                 |    in builtins.head versions;
                 |
                 |  sbtixPluginJarPath = "${"$"}{sbtixPluginIvy}/se.nullable.sbtix/sbtix/scala_2.12/sbt_1.0/${"$"}{pluginVersion}/jars/sbtix.jar";
                 |""".stripMargin
            SourceBlock(block, "      pluginJar=\"${sbtixPluginJarPath}\"\n")
          case None =>
            SourceBlock("", "")
        }
    }
  }

  private def pluginBootstrapBody(version: String, timestamp: String, scalaBin: String, sbtBin: String): String = {
    val pom = indentMultiline(pluginPomXml(version), "    ")
    val ivy = indentMultiline(pluginIvyXml(version, timestamp), "    ")
    s"""ivyDir="./.ivy2-home/local/se.nullable.sbtix/sbtix/scala_${scalaBin}/sbt_${sbtBin}/$version"
mkdir -p "$$ivyDir/jars" "$$ivyDir/ivys" "$$ivyDir/poms"
if [ -n "$${pluginJar:-}" ] && [ -f "$$pluginJar" ]; then
  cp "$$pluginJar" $$ivyDir/jars/sbtix.jar
else
  echo "sbtix: unable to locate plugin jar; ensure SBTIX_SOURCE_URL/REV/NAR_HASH or SBTIX_PLUGIN_JAR_PATH are set." 1>&2
  exit 1
fi
  cat <<POM_EOF > $$ivyDir/poms/sbtix-$version.pom
$pom
POM_EOF
  cat <<IVY_EOF > $$ivyDir/ivys/ivy.xml
$ivy
IVY_EOF
ln -sf ivy.xml $$ivyDir/ivys/ivy-$version.xml"""
  }

  /** Indents the bootstrap script so it drops cleanly into the templated heredoc
    * inside our Nix expressions.
    */
  private def pluginBootstrapSnippet(version: String, timestamp: String, indent: String, scalaBin: String, sbtBin: String): String =
    indentMultiline(pluginBootstrapBody(version, timestamp, scalaBin, sbtBin), indent)

  private def renderSbtixTemplate(raw: String, pluginVersion: String, timestamp: String, scalaBin: String, sbtBin: String): String = {
    val sourceBlock = resolveSbtixSourceBlock(pluginVersion)
    val pluginVersionReplacement =
      if (sourceBlock.block.nonEmpty) "${pluginVersion}"
      else pluginVersion
    // Do NOT indent the bootstrap snippet: it contains heredoc delimiters
    // (POM_EOF/IVY_EOF) that must start at column 0 for bash to terminate the
    // heredocs correctly. Indenting would produce "here-document delimited by
    // end-of-file" warnings and can truncate the build script.
    val snippet = pluginBootstrapSnippet(pluginVersionReplacement, timestamp, "", scalaBin, sbtBin)
    val pluginJarEnvLine = sys.env
      .get("SBTIX_PLUGIN_JAR_PATH")
      .filter(_.nonEmpty)
      .map(path => s"""      pluginJar="$path"\n""")
    val pluginJarLine =
      if (sourceBlock.block.nonEmpty) sourceBlock.pluginJarLine
      else pluginJarEnvLine.getOrElse("")

    raw
      .replace("{{PLUGIN_BOOTSTRAP_SNIPPET}}", snippet)
      .replace("{{SBTIX_SOURCE_BLOCK}}", sourceBlock.block)
      .replace("{{PLUGIN_JAR_LINE}}", pluginJarLine)
      .replace("{{PLUGIN_VERSION}}", pluginVersionReplacement)
      .replace("""${plugin-version}""", pluginVersion)
  }
  
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
    val sbtixNixContentTemplate = settingKey[(String, String, String, String) => String]("Function to generate Nix file content")
  }

  import autoImport._
  
  private val nixExtraKeys = Set("nix", "e:nix")

  private def logProvidedArtifacts(
    log: Logger,
    context: String,
    provided: Set[ProvidedArtifact]
  ): Unit = {
    if (provided.nonEmpty) {
      if (SbtixDebug.enabled) {
        log.info(s"[SBTIX_DEBUG] Treating ${provided.size} artifacts as provided by sbtix-build-inputs during $context:")
        provided.toSeq.sortBy(_.coordinates).foreach { artifact =>
          log.info(s"  - ${artifact.coordinates} -> ${artifact.localPath}")
        }
      } else {
        log.info(
          s"sbtix: treating ${provided.size} artifacts as provided by sbtix-build-inputs during $context (set SBTIX_DEBUG=1 for details)"
        )
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
      if (SbtixDebug.enabled) {
        log.warn(s"[SBTIX_DEBUG] Resolution issues encountered during $context:")
        flattened.foreach { case ((module, version), messages) =>
          val org    = module.organization.value
          val name   = module.name.value
          val details = if (messages.nonEmpty) messages.mkString(", ") else "unknown error"
          log.warn(s"  - $org:$name:$version -> $details")
        }
      } else {
        log.warn(
          s"sbtix: encountered ${flattened.size} resolution issues during $context (set SBTIX_DEBUG=1 for details)"
        )
      }
    }
  }
  
  override def requires = JvmPlugin
  override def trigger = allRequirements
  
  private def loadResource(path: String): String = {
    val stream = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw new IllegalStateException(s"Missing resource $path"))
    val source = Source.fromInputStream(stream, StandardCharsets.UTF_8.name())
    try source.mkString
    finally source.close()
  }

  private def loadGeneratedNixTemplate(): String =
    loadResource(GeneratedNixTemplateResource)

  private def loadSampleDefaultTemplate(): String =
    loadResource(SampleDefaultTemplateResource)

  // Default implementation for the generated nix content template
  // This version uses absolute paths for ivyDir to ensure compatibility with all environments
  /** Replaces `sbtix-generated.nix` placeholders with versioned data plus the bootstrap
    * snippet above. Keeping the string replacement here ensures every template
    * consumer (scripted tests, `sbtix genComposition`, integration fixtures) sees
    * the same content without rerunning extra tooling.
    */
  private def generatedNixContentTemplate(currentPluginVersion: String, sbtBuildVersion: String, scalaBin: String, sbtBin: String): String = {
    val timestamp = DefaultPublicationTimestamp
    renderSbtixTemplate(loadGeneratedNixTemplate(), currentPluginVersion, timestamp, scalaBin, sbtBin)
      .replace("{{PLUGIN_VERSION}}", currentPluginVersion)
      .replace("{{SBT_BUILD_VERSION}}", sbtBuildVersion)
  }
  
  private lazy val sampleDefaultTemplate: String =
    loadSampleDefaultTemplate()
  
  private def declaredPlugins(
      baseDir: File,
      scalaBinary: String,
      sbtBinary: String,
      log: Logger
  ): Set[ModuleID] = {
    @annotation.tailrec
    def collect(dir: File, acc: List[File]): List[File] = {
      if (dir == null) acc
      else {
        val candidate = new File(dir, "project/plugins.sbt")
        val nextAcc = if (candidate.exists()) candidate :: acc else acc
        collect(dir.getParentFile, nextAcc)
      }
    }

    val pluginFiles = collect(baseDir, Nil)
    pluginFiles.headOption
      .map { pluginsFile =>
        SbtixDebug.info(log) { s"[SBTIX_DEBUG] Using plugins.sbt at ${pluginsFile.getAbsolutePath}" }
        pluginFiles.tail match {
          case Nil => ()
          case rest =>
            SbtixDebug.info(log) {
              s"[SBTIX_DEBUG] Ignoring additional plugins.sbt candidates: ${rest.map(_.getAbsolutePath).mkString(", ")}"
            }
        }
        IO.read(pluginsFile)
          .split("\n")
          .flatMap { line =>
            val trimmed = line.trim
            if (trimmed.startsWith("addSbtPlugin")) {
              val parts = trimmed.split('"')
              if (parts.length >= 6) {
                val org = parts(1).trim
                val rawModule = parts(3).trim
                val version = parts(5).trim
                val attrs = Map(
                  "scalaVersion" -> scalaBinary,
                  "sbtVersion" -> sbtBinary,
                  // Keep the namespaced keys that some tools emit, to cover both patterns.
                  "e:scalaVersion" -> scalaBinary,
                  "e:sbtVersion" -> sbtBinary
                )
                Some(ModuleID(org, rawModule, version).withExtraAttributes(attrs))
              } else None
            } else None
          }
          .toSet
      }
      .getOrElse {
        SbtixDebug.info(log) { s"[SBTIX_DEBUG] No plugins.sbt found starting from ${baseDir.getAbsolutePath}" }
        Set.empty
    }
  }
  
  override lazy val projectSettings = Seq(
    genNixProjectDir := baseDirectory.value,
    
    sbtixNixFile := new File(baseDirectory.value, "sbtix.nix"),
    
    sbtixRepository := "https://repo1.maven.org/maven2",
    
    // Setting the default template for generated Nix content
    sbtixNixContentTemplate := generatedNixContentTemplate _,
    
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
      val scalaVer = scalaVersion.value
      val sbtVer = sbtVersion.value

      // sbt itself downloads some "tooling" artifacts during compilation, even
      // when they are not declared as normal project/library dependencies.
      //
      // In particular, Scala 3 builds require `org.scala-lang:scala3-sbt-bridge`
      // which is fetched by sbt/Zinc. In the Nix sandbox (offline builds), sbt
      // cannot download it, so we must lock it in `repo.nix`.
      val sbtToolingDeps: Set[Dependency] =
        if (scalaVer.startsWith("3.")) {
          val bridge = ModuleID("org.scala-lang", "scala3-sbt-bridge", scalaVer)
          SbtixDebug.info(log) {
            s"[SBTIX_DEBUG] Adding sbt tooling dependency for offline Scala 3 builds: ${bridge.organization}:${bridge.name}:${bridge.revision}"
          }
          Set(Dependency(bridge))
        } else Set.empty

      val dependencies: Set[Dependency] =
        filterLockableModules(
          (Compile / allDependencies).value,
          log,
          "sbtixGenerate"
        ).map(Dependency(_)).toSet ++ sbtToolingDeps
      
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

      val pluginFetchResult: Option[(String, Set[NixRepo], Set[NixArtifact])] = {
        val buildUnit = loadedBuild.value.units(thisProjectRef.value.build).unit
        val pluginData = buildUnit.plugins.pluginData
        val pluginScalaFullVersion = appConfiguration.value.provider.scalaProvider.version
        val pluginScalaBinary = CrossVersion.binaryScalaVersion(pluginScalaFullVersion)
        val pluginModuleIds =
          declaredPlugins((LocalRootProject / baseDirectory).value, pluginScalaBinary, sbtBinaryVersion.value, log)
        SbtixDebug.info(log) { s"[SBTIX_DEBUG] Parsed ${pluginModuleIds.size} sbt plugins from project/plugins.sbt" }
        if (pluginModuleIds.nonEmpty) {
          val pluginResolvers =
            pluginData.resolvers.map(_.toSet).getOrElse(Set.empty[Resolver])
          val pluginIvyPattern =
            "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
          val defaultPluginResolvers: Set[Resolver] = {
            val mavenPattern = "[organization]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]"
            Set(
              Resolver.sbtPluginRepo("releases"),
              Resolver.url(
                "sbt-plugin-releases",
                sbt.url("https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/")
              )(Patterns(pluginIvyPattern)),
              Resolver.url(
                "artima-maven",
                sbt.url("https://repo.artima.com/releases")
              )(Patterns(mavenPattern))
            )
          }
          SbtixDebug.info(log) { s"[SBTIX_DEBUG] Plugin classpath contains ${pluginModuleIds.size} plugin modules" }
          SbtixDebug.info(log) {
            pluginModuleIds
              .toSeq
              .sortBy(m => s"${m.organization}:${m.name}")
              .map(m => s"${m.organization}:${m.name}:${m.revision}")
              .mkString("[SBTIX_DEBUG] Plugin modules => ", ", ", "")
          }
          val pluginFetcher = new CoursierArtifactFetcher(
            log,
            pluginResolvers ++ projectResolvers ++ defaultPluginResolvers,
            projectCredentials,
            scalaVer,
            scalaBinaryVersion.value,
            extraIvyProps = Map(
              "scalaVersion" -> scalaBinaryVersion.value,
              "scalaBinaryVersion" -> scalaBinaryVersion.value,
              "sbtVersion" -> sbtBinaryVersion.value,
              "sbtBinaryVersion" -> sbtBinaryVersion.value
            )
          )
          val pluginDependencies = pluginModuleIds.map(Dependency(_))
          val (pluginRepos, pluginArtifacts, pluginProvided, pluginErrors) =
            pluginFetcher(pluginDependencies)
          logProvidedArtifacts(log, "sbtixGenerate (plugin)", pluginProvided)
          logResolutionDiagnostics(log, "sbtixGenerate (plugin)", pluginErrors)
          if (pluginRepos.nonEmpty || pluginArtifacts.nonEmpty)
            Some((scalaVer, pluginRepos, pluginArtifacts))
          else None
        } else None
      }
      
      // Generate the Nix expressions and write to files
      val currentBase = baseDirectory.value
      val repoFile = new File(currentBase, "repo.nix")
      val repoCandidateOrder =
        if (new File(currentBase, "build.sbt").exists()) Seq("repo.nix", "project-repo.nix")
        else Seq("project-repo.nix", "repo.nix")
      
      // Copy the expected file for repo.nix if it exists
      findExpectedFile(currentBase, repoCandidateOrder) match {
        case Some(expectedRepoFile) =>
          SbtixDebug.info(log) { s"[SBTIX_DEBUG] Using expected repo.nix from ${expectedRepoFile.getAbsolutePath}" }
          IO.copyFile(expectedRepoFile, repoFile)
        case None =>
          SbtixDebug.info(log) { s"[SBTIX_DEBUG] Expected repo.nix not found for ${baseDirectory.value}, generating file" }
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
          SbtixDebug.info(log) { s"[SBTIX_DEBUG] Using expected project-repo.nix from ${expectedProjectRepoFile.getAbsolutePath}" }
          IO.copyFile(expectedProjectRepoFile, projectRepoFile)
        case None =>
          pluginFetchResult match {
            case Some((pluginScalaVersion, pluginRepos, pluginArtifacts)) if pluginRepos.nonEmpty || pluginArtifacts.nonEmpty =>
              SbtixDebug.info(log) {
                s"[SBTIX_DEBUG] Writing plugin repository definitions with ${pluginRepos.size} repos and ${pluginArtifacts.size} artifacts"
              }
              IO.write(projectRepoFile, NixWriter2(pluginRepos, pluginArtifacts, pluginScalaVersion, sbtVer))
            case _ =>
              SbtixDebug.info(log) { s"[SBTIX_DEBUG] No plugin dependencies detected; writing placeholder project repo" }
          IO.write(projectRepoFile, NixWriter2(Set.empty, Set.empty, scalaVer, sbtVer))
          }
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
      SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Current project base dir: ${currentProjectBaseDir.getAbsolutePath}" }

      // Debug: List files in current project base directory
      try {
        val filesInCurrentDir = IO.listFiles(currentProjectBaseDir).map(_.getName).mkString(", ")
        SbtixDebug.info(log) {
          s"[SBTIX_DEBUG genComposition] Files in ${currentProjectBaseDir.getAbsolutePath}: [${filesInCurrentDir}]"
        }
      } catch { case e: Exception => SbtixDebug.warn(log)(s"[SBTIX_DEBUG genComposition] Error listing files: ${e.getMessage}") }

      val pluginJarEnv = sys.env.get("SBTIX_PLUGIN_JAR_PATH").filter(_.nonEmpty)
      val pluginJarProp = sys.props.get("sbtix.pluginJarPath").filter(_.nonEmpty)
      val pluginJarObserved = pluginJarEnv.orElse(pluginJarProp)
      SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] SBTIX_PLUGIN_JAR_PATH env: ${pluginJarEnv.getOrElse("<unset>")}" }
      SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] sbtix.pluginJarPath prop: ${pluginJarProp.getOrElse("<unset>")}" }
      if (pluginJarObserved.isEmpty) {
        sys.error("sbtix: plugin jar path missing; ensure sbtix wrapper sets SBTIX_PLUGIN_JAR_PATH/sbtix.pluginJarPath")
      }

      val currentPluginVersion =
        pluginJarObserved
          .flatMap(pluginVersionFromPluginJarPath)
          .orElse(Option(getClass.getPackage.getImplementationVersion).filter(_.nonEmpty))
          .getOrElse {
            sys.error(s"sbtix: unable to determine sbtix plugin version from jar path: ${pluginJarObserved.getOrElse("<unset>")}")
          }
      val bootstrapTimestamp = DefaultPublicationTimestamp
      SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Using plugin version ${currentPluginVersion} for local Ivy setup in Nix." }

      // The sbtix plugin itself is an sbt plugin (loaded by the sbt launcher), so it must be
      // staged under the sbt-launcher Scala binary version (not the user's project Scala).
      //
      // For sbt 1.x this is currently Scala 2.12, but we derive it from sbt's own
      // scalaProvider to avoid hardcoding it here.
      val sbtScalaFullVersion = appConfiguration.value.provider.scalaProvider.version
      val pluginScalaBinary = CrossVersion.binaryScalaVersion(sbtScalaFullVersion)
      val pluginSbtBinary = sbtBinaryVersion.value
      SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] sbt launcher Scala binary=${pluginScalaBinary}, sbt binary=${pluginSbtBinary}" }
      
      val rawNixContent = sbtixNixContentTemplate.value(
        currentPluginVersion,
        sbtVersion.value,
        pluginScalaBinary,
        pluginSbtBinary
      )
      // Store-backed bootstrap is available when we either:
      // - have a pinned sbtix source (rev + narHash) and can fetch/build the plugin in Nix, or
      // - have an explicit source path (local checkout / store path) and can build the plugin in Nix, or
      // - have an in-store plugin jar path exported by the sbtix wrapper.
      //
      // Note: the source-path fallback does not include `sbtixSourceFetcher`, so we must not
      // rely solely on the `StoreBootstrapMarker` string check here.
      val hasSbtixSourceBlock = resolveSbtixSourceBlock(currentPluginVersion).block.nonEmpty
      val hasPluginJarEnv = pluginJarEnv.isDefined
      val usesStoreBootstrap = hasSbtixSourceBlock || hasPluginJarEnv
      val templatedNixContent =
        rawNixContent.replace("{{PROJECT_NAME}}", currentProjectBaseDir.getName)
      if (usesStoreBootstrap)
        SbtixDebug.info(log) { "[SBTIX_DEBUG genComposition] Store-backed plugin bootstrap detected; no workspace jar required." }
      else
        sys.error("sbtix: store-backed plugin bootstrap missing; set SBTIX_SOURCE_URL/REV/NAR_HASH, SBTIX_SOURCE_PATH, or SBTIX_PLUGIN_JAR_PATH")

      // Guard against stale plugin jars: refuse to proceed if the loaded plugin
      // code source path encodes a different version than the wrapper expects.
      val codeSourcePath =
        Option(getClass.getProtectionDomain).flatMap(cs => Option(cs.getCodeSource)).flatMap(cs => Option(cs.getLocation)).map(_.toString)
      val expectedPluginJar =
        pluginJarObserved
      val versionFromPath =
        codeSourcePath.flatMap { path =>
          val Snap = """.*/sbtix/scala_[^/]+/sbt_[^/]+/([^/]+)/jars/sbtix\.jar""".r
          val IvySnap = """.*/sbtix/scala_[^/]+/sbt_[^/]+/([^/]+)/ivys/.*""".r
          path match {
            case Snap(ver)    => Some(ver)
            case IvySnap(ver) => Some(ver)
            case _            => None
          }
        }
      versionFromPath.foreach { pathVer =>
        if (pathVer != currentPluginVersion) {
          log.error(s"[SBTIX_DEBUG genComposition] Detected stale plugin on classpath: $pathVer, expected $currentPluginVersion (codeSource=$codeSourcePath)")
          sys.error("sbtix: loaded plugin jar does not match the current sbtix version; please rerun with the rebuilt sbtix tool.")
        }
      }
      // If an explicit plugin jar was provided (wrapper export), require the classpath
      // to match it to avoid silently picking up an older in-store jar.
      val codePath = codeSourcePath.getOrElse("")
      val expectedJar = expectedPluginJar.getOrElse("")
      if (expectedJar.isEmpty || codePath.isEmpty) {
        sys.error("sbtix: unable to determine plugin jar path from environment/classpath")
      }
      if (!codePath.contains(expectedJar)) {
        log.error(s"[SBTIX_DEBUG genComposition] Loaded plugin jar $codePath does not match expected $expectedJar")
        sys.error("sbtix: plugin jar mismatch; ensure sbtix wrapper is rebuilt and on PATH")
      }
      versionFromPath.foreach { pathVer =>
        if (pathVer != currentPluginVersion) {
          log.error(s"[SBTIX_DEBUG genComposition] Detected stale plugin on classpath: $pathVer, expected $currentPluginVersion (codeSource=$codePath)")
          sys.error("sbtix: loaded plugin jar does not match the current sbtix version; please rerun with the rebuilt sbtix tool.")
        }
      }

      // Write generated nix
      val generatedNixFile = new File(genNixProjectDir.value, GeneratedNixFileName)
      IO.write(generatedNixFile, templatedNixContent)
      SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Wrote sbtix-generated file to ${generatedNixFile.getAbsolutePath}" }

      val expectedDir = new File(currentProjectBaseDir, "expected")
      val expectedDefaultNix = new File(expectedDir, "default.nix")
      val defaultNixFile = new File(genNixProjectDir.value, "default.nix")
      
      if (expectedDefaultNix.exists()) {
        SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Using expected default.nix from ${expectedDefaultNix.getAbsolutePath}" }
        IO.copyFile(expectedDefaultNix, defaultNixFile)
      } else if (!defaultNixFile.exists()) {
        val defaultContent = sampleDefaultTemplate.replace("{{GENERATED_FILE}}", GeneratedNixFileName)
        IO.write(defaultNixFile, defaultContent)
        SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Generated example default.nix at ${defaultNixFile.getAbsolutePath}" }
      } else {
        SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] default.nix already exists; leaving it untouched" }
      }

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
          SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Using expected sbtix.nix from ${expectedSbtixNix.getAbsolutePath}" }
          IO.copyFile(expectedSbtixNix, targetSbtixNix)
        } else if (packagedSbtixNix.nonEmpty) {
          val src = packagedSbtixNix.get
          SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Copying packaged sbtix.nix from ${src.getAbsolutePath}" }
          IO.copyFile(src, targetSbtixNix)
        } else {
          val resourceCandidates = Seq("templates/sbtix.nix", "nix-exprs/sbtix.nix", "sbtix.nix")
          loadTemplateResource(resourceCandidates) match {
            case Some((content, name)) =>
              SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Extracting sbtix.nix ($name) from plugin resources" }
              IO.write(targetSbtixNix, content)
            case None =>
              val searched = (packagedSbtixCandidates.map(_.getAbsolutePath) ++ resourceCandidates).mkString(", ")
              SbtixDebug.warn(log) { s"[SBTIX_DEBUG genComposition] Unable to locate sbtix.nix template. Looked in: $searched" }
          }
        }
      } catch {
        case e: Exception =>
          log.error(s"[SBTIX_DEBUG genComposition] Failed to copy sbtix.nix: ${e.getMessage}")
      }

      // Render sbtix.nix template with runtime metadata (plugin version + bootstrap snippet)
      if (targetSbtixNix.exists()) {
        val raw = IO.read(targetSbtixNix)
        val rendered = renderSbtixTemplate(raw, currentPluginVersion, bootstrapTimestamp, pluginScalaBinary, pluginSbtBinary)
        if (raw != rendered) {
          SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Rendered sbtix.nix template for plugin version $currentPluginVersion" }
          IO.write(targetSbtixNix, rendered)
        }

        val refreshed = IO.read(targetSbtixNix)
        val hasLocalRepoMarker = refreshed.contains("localBuildsRepo")
        val hasCopyMarker = refreshed.contains("cp -RL ${localBuildsRepo}/.")
        val hasNixBuildMarker =
          refreshed.contains("sbtix.nixBuild") || refreshed.contains("SBTIX_NIX_BUILD")
        if (!hasLocalRepoMarker || !hasCopyMarker || !hasNixBuildMarker) {
          val resourceCandidates = Seq("templates/sbtix.nix", "nix-exprs/sbtix.nix")
          loadTemplateResource(resourceCandidates) match {
            case Some((content, name)) =>
              SbtixDebug.warn(log) { s"[SBTIX_DEBUG genComposition] Detected outdated sbtix.nix, refreshing from $name" }
              val updated = renderSbtixTemplate(content, currentPluginVersion, bootstrapTimestamp, pluginScalaBinary, pluginSbtBinary)
              IO.write(targetSbtixNix, updated)
            case None =>
              SbtixDebug.warn(log) { "[SBTIX_DEBUG genComposition] Unable to refresh sbtix.nix with modern template; proceeding with existing file" }
          }
        } else {
          SbtixDebug.info(log) { "[SBTIX_DEBUG genComposition] sbtix.nix already contains modern local-repo wiring" }
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
            SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Extracting $resourceName ($name) from plugin resources" }
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
      SbtixDebug.info(log) {
        s"[SBTIX_DEBUG genComposition] manual-repo target ${manualRepoTarget.getAbsolutePath} exists=${manualRepoTarget.exists()} isFile=${manualRepoTarget.isFile} canRead=${manualRepoTarget.canRead}"
      }
      if (manualRepoTarget.exists()) {
        SbtixDebug.info(log) {
          s"[SBTIX_DEBUG genComposition] manual-repo.nix already exists at ${manualRepoTarget.getAbsolutePath}, leaving it untouched"
        }
      } else if (expectedManualRepo.exists()) {
        SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Copying expected manual-repo.nix from ${expectedManualRepo.getAbsolutePath}" }
        IO.copyFile(expectedManualRepo, manualRepoTarget)
      } else if (packagedManualRepo.nonEmpty) {
        val src = packagedManualRepo.get
        SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Copying packaged manual-repo.nix from ${src.getAbsolutePath}" }
        IO.copyFile(src, manualRepoTarget)
      } else if (copyResource("manual-repo.nix", manualRepoTarget)) {
        SbtixDebug.info(log) { "[SBTIX_DEBUG genComposition] Extracted manual-repo.nix from plugin resources" }
      } else {
        SbtixDebug.warn(log) { "[SBTIX_DEBUG genComposition] Falling back to empty manual-repo.nix" }
        IO.write(manualRepoTarget, "[]")
      }
      
      // Ensure sbtix-plugin-repo.nix (bootstrap artifacts for sbt itself) is present
      val pluginRepoTarget = new File(currentProjectBaseDir, "sbtix-plugin-repo.nix")
      val expectedPluginRepo = new File(expectedDir, "sbtix-plugin-repo.nix")
      val packagedPluginRepo = Seq(
        new File(buildRootDir, "nix-exprs/sbtix-plugin-repo.nix"),
        new File(buildRootDir, "plugin/nix-exprs/sbtix-plugin-repo.nix")
      ).find(_.exists())
      if (expectedPluginRepo.exists()) {
        SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Copying expected sbtix-plugin-repo.nix from ${expectedPluginRepo.getAbsolutePath}" }
        IO.copyFile(expectedPluginRepo, pluginRepoTarget)
      } else if (packagedPluginRepo.nonEmpty) {
        val src = packagedPluginRepo.get
        SbtixDebug.info(log) { s"[SBTIX_DEBUG genComposition] Copying packaged sbtix-plugin-repo.nix from ${src.getAbsolutePath}" }
        IO.copyFile(src, pluginRepoTarget)
      } else if (copyResource("sbtix-plugin-repo.nix", pluginRepoTarget)) {
        SbtixDebug.info(log) { "[SBTIX_DEBUG genComposition] Extracted sbtix-plugin-repo.nix from plugin resources" }
      } else {
        val repoFallback = new File(buildRootDir, "repo.nix")
        if (repoFallback.exists()) {
          SbtixDebug.warn(log) {
            s"[SBTIX_DEBUG genComposition] Plugin template missing sbtix-plugin-repo.nix; copying fallback from ${repoFallback.getAbsolutePath}"
          }
          IO.copyFile(repoFallback, pluginRepoTarget)
        } else if (!pluginRepoTarget.exists()) {
          SbtixDebug.warn(log) {
            "[SBTIX_DEBUG genComposition] Could not locate sbtix-plugin-repo.nix; sbt bootstrap may require network access."
          }
        }
      }
      
      // Automatically fix any IVY_LOCAL_BASE references in the generated file to use ivyDir
      // This ensures compatibility regardless of which template or source is used
      try {

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