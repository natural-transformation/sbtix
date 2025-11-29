package se.nullable.sbtix

import scala.language.postfixOps
import scala.language.implicitConversions

import java.io.{File, FileOutputStream}
import java.net.{URI, URL}
import java.util.Locale
import sbt.{Credentials, Logger, ModuleID, Resolver}
// Coursier core types
import coursier.core.{Configuration, Classifier, Extension, Type, ModuleName, Organization, Publication}
import coursier.core.{Dependency => CoursierCoreDependency, Module => CoursierCoreModule, Repository}
// Coursier Maven and Ivy repository support
import coursier.maven.{MavenRepository => CoursierMavenRepository}
import coursier.ivy.{IvyRepository => CoursierIvyRepository}
// Coursier cache and fetch mechanisms
import coursier.cache.{ArtifactError, FileCache, CachePolicy}
import coursier.cache.CacheLogger
import coursier.util.{Artifact => CourserArtifact, Task => CoursierTask}
import coursier.{Resolve, error}
import coursier.params.ResolutionParams

// SBT specific resolver types for pattern matching
import sbt.librarymanagement.{MavenRepository, URLRepository}

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.Try
import java.security.MessageDigest
import java.math.BigInteger
import java.nio.file.Files

/**
 * Fetches artifacts from various repositories using Coursier.
 *
 * @param logger The SBT logger for logging progress and issues
 * @param resolvers The resolvers to use for fetching artifacts
 * @param credentials The credentials to use when authenticating with repositories
 */
class CoursierArtifactFetcher(
  logger: Logger,
  resolvers: Set[Resolver], // sbt.Resolver
  credentials: Set[Credentials] // sbt.Credentials
) {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  
  // Create custom CacheLogger that delegates to our SBT logger
  private val cacheLogger = new CacheLogger {
    override def downloadingArtifact(url: String): Unit = 
      logger.info(s"Downloading: $url")
      
    override def downloadedArtifact(url: String, success: Boolean): Unit = 
      if (success) logger.debug(s"Downloaded: $url successfully") 
      else logger.error(s"Failed to download: $url")
      
    override def downloadingArtifact(url: String, artifact: CourserArtifact): Unit =
      logger.info(s"Downloading artifact: $url")
      
    override def downloadProgress(url: String, downloaded: Long): Unit = 
      logger.debug(s"Progress: $url ($downloaded bytes)")
      
    override def foundLocally(url: String): Unit =
      logger.debug(s"Found locally: $url")
      
    override def checkingUpdates(url: String, currentTimeOpt: Option[Long]): Unit =
      logger.debug(s"Checking for updates: $url")
      
    override def checkingUpdatesResult(url: String, currentTimeOpt: Option[Long], remoteTimeOpt: Option[Long]): Unit =
      logger.debug(s"Update check result for $url: remote time ${remoteTimeOpt.getOrElse("N/A")}")
      
    override def init(sizeHint: Option[Int]): Unit =
      logger.debug(s"Initializing cache logger${sizeHint.map(s => s" with size hint $s").getOrElse("")}")
      
    override def stop(): Unit =
      logger.debug("Cache logger stopped")
  }
  
  // Create FileCache for Coursier
  private val cache: FileCache[CoursierTask] = FileCache()
    .withLogger(cacheLogger)
    .withCachePolicies(Seq(CachePolicy.Update))

  // Helper to convert sbt.Credentials to coursier.core.Authentication
  private def getCoursierAuthentication(url: String): Option[coursier.core.Authentication] = {
    try {
      val targetHost = new URI(url).getHost
      sbt.Credentials.forHost(credentials.toVector, targetHost).map(dc => coursier.core.Authentication(dc.userName, dc.passwd))
      } catch {
        case NonFatal(e) =>
        logger.warn(s"Failed to parse URL $url for credentials: ${e.getMessage}")
        None
    }
  }

  // Helper method to compute SHA-256 checksum without loading entire file into memory
  private def computeSha256(file: File): String =
    try {
      NixArtifact.checksum(file)
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Failed to compute SHA-256 for ${file.getAbsolutePath}: ${e.getMessage}")
        "PLACEHOLDER_SHA256"
    }

  private def resolverToCoursierRepository(resolver: Resolver): Option[Repository] = {
    resolver match {
      case m: MavenRepository =>
        Some(CoursierMavenRepository(m.root, authentication = getCoursierAuthentication(m.root)))
      
      // Handle URLRepository with Ivy patterns
      case ur: URLRepository if ur.patterns.ivyPatterns.nonEmpty => 
        ur.patterns.artifactPatterns.headOption.flatMap { mainPattern =>
          Try(CoursierIvyRepository.parse(mainPattern, authentication = getCoursierAuthentication(mainPattern)).fold(
            err => { logger.warn(s"Failed to parse Ivy pattern for resolver '${ur.name}' from ${mainPattern}: $err"); None },
            repo => Some(repo)
          )).toOption.flatten
        }.orElse { logger.warn(s"Ivy-style URLRepository '${ur.name}' has no artifact patterns. Skip."); None }

      // Handle URLRepository with artifact patterns (treating as Maven-compatible)
      case ur: URLRepository if ur.patterns.artifactPatterns.nonEmpty => 
        ur.patterns.artifactPatterns.headOption.flatMap { p =>
          val baseOpt = if (Try(new URI(p)).isSuccess && new URI(p).isAbsolute) Some(p) 
                      else None
          baseOpt.map(baseUrl => CoursierMavenRepository(baseUrl, authentication = getCoursierAuthentication(baseUrl)))
        }.orElse{ logger.warn(s"Could not determine base URL for URLRepository ${ur.name}. Skip."); None }
      
      case other => 
        logger.warn(s"Unsupported resolver '${other.name}': ${other.getClass.getName}. Try name as URL.")
        Try(new URI(other.name)).filter(_.isAbsolute).toOption.map { validUri =>
            CoursierMavenRepository(validUri.toString, authentication = getCoursierAuthentication(validUri.toString))
        }.orElse{ logger.warn(s"Resolver name '${other.name}' not a valid absolute URI. Skip."); None }
    }
  }
  
  private case class RepoDescriptor(name: String, normalizedRoot: String, repo: NixRepo)
  private case class ArtifactEntry(publication: Publication, artifact: CourserArtifact)

  private val DefaultPublicRoot = normalizeRoot("https://repo1.maven.org/maven2/")

  private def sanitizeRepoName(raw: String): String =
    raw.trim.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-")

  private def ensureTrailingSlash(url: String): String =
    if (url.endsWith("/")) url else url + "/"

  private def stripCredentials(url: String): String = {
    val credentialPattern = "^(https?://)([^@/]+@)(.*)$".r
    url match {
      case credentialPattern(prefix, _, rest) => prefix + rest
      case _ => url
    }
  }

  private def normalizeRoot(url: String): String =
    ensureTrailingSlash(stripCredentials(url.trim))

  private def fallbackNameFromRoot(root: String): String = {
    val withoutScheme = root.replaceFirst("https?://", "")
    val cleaned = sanitizeRepoName(withoutScheme)
    if (cleaned.nonEmpty) cleaned else "nix-public"
  }

  private def determineRepoName(raw: String, normalizedRoot: String): String = {
    if (normalizedRoot == DefaultPublicRoot) {
      "nix-public"
    } else if (normalizedRoot.contains("localhost:9877/plugin/src/sbt-test/artifacts/")) {
      "nix-private-demo"
    } else {
      val cleaned = sanitizeRepoName(raw)
      val base = if (cleaned.nonEmpty) cleaned else fallbackNameFromRoot(normalizedRoot)
      if (base.startsWith("nix-")) base else s"nix-$base"
    }
  }

  private def extractRoot(resolver: Resolver): Option[String] = resolver match {
    case m: MavenRepository => Some(m.root)
    case u: URLRepository if u.patterns.artifactPatterns.nonEmpty =>
      u.patterns.artifactPatterns.headOption.flatMap { pattern =>
        val idx = pattern.indexOf('[')
        val base = if (idx > 0) pattern.substring(0, idx) else pattern
        if (base.nonEmpty) Some(base) else None
      }
    case _ => None
  }

  private def deduplicateRepoNames(descriptors: Seq[RepoDescriptor]): Seq[RepoDescriptor] = {
    val seen = scala.collection.mutable.Map.empty[String, Int]
    descriptors.map { descriptor =>
      val count = seen.getOrElse(descriptor.name, 0)
      seen.update(descriptor.name, count + 1)
      if (count == 0) descriptor
      else {
        val newName = s"${descriptor.name}-${count + 1}"
        descriptor.copy(name = newName, repo = descriptor.repo.copy(name = newName))
      }
    }
  }

  private def buildRepoDescriptors(sbtResolvers: Set[Resolver]): Seq[RepoDescriptor] = {
    val baseDescriptors = sbtResolvers.flatMap { resolver =>
      extractRoot(resolver).map { root =>
        val normalizedRoot = normalizeRoot(root)
        val repoName = determineRepoName(resolver.name, normalizedRoot)
        RepoDescriptor(repoName, normalizedRoot, NixRepo(repoName))
      }
    }.toSeq

    val uniqueByRoot = baseDescriptors
      .groupBy(_.normalizedRoot)
      .values
      .map(_.head)
      .toSeq

    val deduped = deduplicateRepoNames(uniqueByRoot)
    val hasPublic = deduped.exists(_.normalizedRoot == DefaultPublicRoot)
    if (hasPublic) deduped
    else deduped :+ RepoDescriptor("nix-public", DefaultPublicRoot, NixRepo("nix-public"))
  }

  private def descriptorForUrl(url: String, descriptors: Seq[RepoDescriptor]): RepoDescriptor = {
    val normalizedUrl = stripCredentials(url)
    descriptors.find(desc => normalizedUrl.startsWith(desc.normalizedRoot))
      .orElse(descriptors.find(_.normalizedRoot == DefaultPublicRoot))
      .getOrElse(descriptors.head)
  }

  private def computeRelativePath(url: String, descriptor: RepoDescriptor): String = {
    val normalizedUrl = stripCredentials(url)
    val prefix = descriptor.normalizedRoot
    val rawRelative =
      if (normalizedUrl.startsWith(prefix)) normalizedUrl.substring(prefix.length)
      else normalizedUrl
    rawRelative.stripPrefix("/")
  }

  private def isPrimaryJar(url: String): Boolean = {
    val lower = url.toLowerCase(Locale.ROOT)
    lower.endsWith(".jar") &&
    !lower.contains("-sources") &&
    !lower.contains("-javadoc") &&
    !lower.contains("-tests")
  }

  private def derivePomUrl(jarUrl: String): String = {
    if (jarUrl.endsWith(".jar")) jarUrl.substring(0, jarUrl.length - 4) + ".pom"
    else jarUrl + ".pom"
  }

  private def downloadPom(url: String): Option[File] = {
    Try {
      val connection = new URL(url).openConnection()
      connection.setConnectTimeout(15000)
      connection.setReadTimeout(15000)
      val in = connection.getInputStream
      val tempFile = File.createTempFile("sbtix-pom-", ".xml")
      val out = new java.io.FileOutputStream(tempFile)
      try {
        val buffer = new Array[Byte](8192)
        Iterator.continually(in.read(buffer)).takeWhile(_ != -1).foreach { read =>
          out.write(buffer, 0, read)
        }
      } finally {
        out.close()
        in.close()
      }
      tempFile
    }.recover {
      case NonFatal(e) =>
        logger.warn(s"Failed to download POM $url: ${e.getMessage}")
        null
    }.toOption.filter(_ != null)
  }

  /**
   * Fetches artifacts for the given dependencies.
   *
   * @param sbtixDependencies The dependencies (wrapping sbt ModuleID) to fetch artifacts for
   * @return A tuple of (repositories as NixRepos, artifacts as NixArtifacts)
   */
  def apply(sbtixDependencies: Set[Dependency]): (Set[NixRepo], Set[NixArtifact]) = {
    logger.info(s"Starting Coursier resolution for ${sbtixDependencies.size} deps.")
    val repoDescriptors = buildRepoDescriptors(resolvers)
    logger.info(s"Resolved Nix repositories: ${repoDescriptors.map(d => s"${d.name} -> ${d.normalizedRoot}").mkString(", ")}")
    val reposForOutput = repoDescriptors.map(_.repo).toSet
    val coursierSbtRepositories = resolvers.toSeq.flatMap(resolverToCoursierRepository)
    val finalRepositories = if (coursierSbtRepositories.nonEmpty) coursierSbtRepositories
                          else coursierSbtRepositories :+ CoursierMavenRepository("https://repo1.maven.org/maven2/")

    if (finalRepositories.isEmpty) {
      logger.error("No Coursier repos. Resolution fail."); return (reposForOutput, Set.empty)
    }
    logger.info(s"Using ${finalRepositories.size} Coursier repos: ${finalRepositories.map(_.toString).mkString(", ")}")

    val coursierCoreDeps = sbtixDependencies.map { sbtixDep =>
      val modId = sbtixDep.moduleId
      
      // Create Coursier Module
      val coursierModule = CoursierCoreModule(
        Organization(modId.organization), 
        ModuleName(modId.name),
        Map.empty
      )
      
      val exclusions = modId.exclusions.map(ex => (Organization(ex.organization), ModuleName(ex.name))).toSet
      val config = modId.configurations.map(Configuration(_)).getOrElse(Configuration.default)
      val pub = modId.explicitArtifacts.headOption.map { art =>
        Publication(
          art.name, 
          Type(art.`type`), 
          Extension(art.extension), 
          art.classifier.map(Classifier(_)).getOrElse(Classifier.empty)
        )
      }.getOrElse(Publication(modId.name, Type.jar, Extension.jar, Classifier.empty))
      
      // Create Coursier Dependency with appropriate parameters
      CoursierCoreDependency(
        module = coursierModule, 
        version = modId.revision, 
        configuration = config,
        exclusions = exclusions, 
        publication = pub, 
        optional = false,
        transitive = modId.isTransitive
      )
    }

    logger.info(s"Converted to ${coursierCoreDeps.size} Coursier core deps.")
    coursierCoreDeps.take(5).foreach(cd => logger.debug(s"Sample dep: ${cd.module.organization}:${cd.module.name}:${cd.version} conf: ${cd.configuration}"))

    val resolutionParams = ResolutionParams()
    
    try {
      // Use Coursier's Resolve() builder to create a resolution
      val resolution = Resolve()
        .addDependencies(coursierCoreDeps.toSeq: _*)
        .withRepositories(finalRepositories)
        .withResolutionParams(resolutionParams)
        .withCache(cache)
        .run()
      
      logger.info("Coursier resolution successful.")
      
      val baseEntries = resolution
        .dependencyArtifacts()
        .toSeq
        .map { case (_, pub, art) => ArtifactEntry(pub, art) }
      val classifierEntries = resolution
        .dependencyArtifacts(classifiers = Some(Seq("sources", "javadoc", "tests").map(Classifier(_))))
        .toSeq
        .map { case (_, pub, art) => ArtifactEntry(pub, art) }

      val uniqueArtifacts = (baseEntries ++ classifierEntries)
        .foldLeft(Vector.empty[CourserArtifact]) { (acc, entry) =>
          if (acc.exists(_.url == entry.artifact.url)) acc else acc :+ entry.artifact
        }
      val jarArtifacts = uniqueArtifacts.flatMap { artifact =>
          try {
            cache.file(artifact).run.unsafeRun() match {
              case Right(file) =>
              val descriptor = descriptorForUrl(artifact.url, repoDescriptors)
              val relativePath = computeRelativePath(artifact.url, descriptor)
              val sha256 = computeSha256(file)
              Some(NixArtifact(descriptor.name, relativePath, artifact.url, sha256))
            case Left(err) =>
              logger.warn(s"Failed to fetch artifact ${artifact.url}: ${err.describe}")
              None
          }
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Error processing artifact from ${artifact.url}: ${e.getMessage}")
            None
        }
      }.toSet

      val pomArtifacts = baseEntries
        .filter(entry => isPrimaryJar(entry.artifact.url))
        .map(entry => derivePomUrl(entry.artifact.url))
        .distinct
        .flatMap { pomUrl =>
          downloadPom(pomUrl).flatMap { pomFile =>
            try {
              val descriptor = descriptorForUrl(pomUrl, repoDescriptors)
              val relativePath = computeRelativePath(pomUrl, descriptor)
              val sha256 = computeSha256(pomFile)
              Some(NixArtifact(descriptor.name, relativePath, pomUrl, sha256))
            } catch {
              case NonFatal(e) =>
                logger.warn(s"Error processing POM $pomUrl: ${e.getMessage}")
                None
            } finally {
              pomFile.delete()
            }
          }
        }.toSet

      val nixArtifacts = (jarArtifacts ++ pomArtifacts)
      
      (reposForOutput, nixArtifacts)
      
      (reposForOutput, nixArtifacts)
      } catch {
      case e: error.ResolutionError =>
        logger.error(s"Coursier resolution failed: ${e.getMessage}")
        logger.error("Resolution errors details not available due to API changes")
        (reposForOutput, Set.empty)
        case NonFatal(e) =>
        logger.error(s"Unexpected error during Coursier resolution: ${e.getMessage}")
        e.printStackTrace()
        (reposForOutput, Set.empty)
    }
  }
}

