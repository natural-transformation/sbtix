package se.nullable.sbtix

import scala.language.postfixOps
import scala.language.implicitConversions

import java.io.{File, FileOutputStream}
import java.net.{URI, URL}
import java.util.Locale
import java.nio.file.{Files => NioFiles}
import java.util.concurrent.{ConcurrentSkipListSet, ExecutorService}
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.Try

import sbt.{uri, Credentials, Logger, ModuleID, Resolver}
import sbt.librarymanagement.{CrossVersion, FileRepository, MavenRepository, PatternsBasedRepository, URLRepository}

import coursier.cache.{Cache, CacheDefaults, CacheLogger, CachePolicy, FileCache}
import coursier.core.{Classifier, Configuration, Dependency => CDependency, Module => CModule, ModuleName, Organization, Publication, Repository, Resolution, Type, Extension}
import coursier.core.ResolutionProcess
import coursier.util.{Artifact => CArtifact, EitherT, Task}

import lmcoursier.internal.shaded.coursier.core.Authentication
import lmcoursier.internal.Resolvers

import se.nullable.sbtix.data.RoseTree
import se.nullable.sbtix.{Dependency => SbtixDependency}
import se.nullable.sbtix.utils.Conversions.convertRepository

case class MetaArtifact(artifactUrl: String, checkSum: String) extends Comparable[MetaArtifact] {
  override def compareTo(other: MetaArtifact): Int =
    artifactUrl.compareTo(other.artifactUrl)
}

  /** Minimal POM model to capture parents and import-scoped BOMs. */
  private object PomModel {
    final case class PomDep(groupId: String, artifactId: String, version: String, scope: String = "", `type`: String = "pom")
    final case class Model(parents: List[PomDep], importBoms: Set[PomDep], optionalDeps: Set[PomDep])

    def parse(file: File): Model = {
      val xml = scala.xml.XML.loadFile(file)
      val parent = (xml \ "parent").headOption.map { p =>
        PomDep(
          (p \ "groupId").text.trim,
          (p \ "artifactId").text.trim,
          (p \ "version").text.trim
        )
      }.toList
      val deps = (xml \ "dependencyManagement" \ "dependencies" \ "dependency").map { d =>
        PomDep(
          (d \ "groupId").text.trim,
          (d \ "artifactId").text.trim,
          (d \ "version").text.trim,
          scope = (d \ "scope").text.trim,
          `type` = (d \ "type").text.trim match {
            case "" => "pom"
            case other => other
          }
        )
      }.toSet
      val importBoms = deps.filter(d => d.scope == "import" && d.`type` == "pom")
      val optionalDeps = deps.filter(d => d.scope == "optional" || (d.scope.isEmpty && d.`type` == "pom"))
      Model(parent, importBoms, optionalDeps)
    }
  }

case class ProvidedArtifact(
  organization: String,
  name: String,
  version: String,
  localPath: String,
  url: String
) {
  val coordinates: String = s"$organization:$name:$version"
}

object ProvidedArtifact {
  def from(dependency: CDependency, url: String): ProvidedArtifact = {
    val file = new File(new URI(url))
    ProvidedArtifact(
      dependency.module.organization.value,
      dependency.module.name.value,
      dependency.version,
      file.getCanonicalPath,
      url
    )
  }
}

class CoursierArtifactFetcher(
  logger: Logger,
  resolvers: Set[Resolver],
  credentials: Set[Credentials],
  scalaVersion: String,
  scalaBinaryVersion: String,
  extraIvyProps: Map[String, String] = Map.empty
) {

  private val metaArtifactCollector = new ConcurrentSkipListSet[MetaArtifact]()
  private val ivyLocalResolver: Resolver =
    Resolver.file(
      "sbtix-ivy-local",
      new File(new File(sys.props("user.home"), ".ivy2"), "local")
    )(Resolver.ivyStylePatterns)

  private val effectiveResolvers: Set[Resolver] =
    if (resolvers.exists(_.name == ivyLocalResolver.name)) resolvers else resolvers + ivyLocalResolver
  private val sbtVersionKeys = Set("sbtVersion", "e:sbtVersion")
  private val sbtBinaryVersionAttr: Option[String] =
    extraIvyProps.get("sbtBinaryVersion").orElse(extraIvyProps.get("sbtVersion"))

  def apply(depends: Set[SbtixDependency]): (Set[NixRepo], Set[NixArtifact], Set[ProvidedArtifact], Set[ResolutionErrors]) = {
    SbtixDebug.info(logger) {
      s"[SBTIX_DEBUG] Effective resolvers: ${effectiveResolvers.map(_.name).mkString(", ")}; ivyLocal=${ivyLocalCanonical.getPath}"
    }
    val coursierDeps = depends.flatMap(expandSbtPluginDependency).map(toCoursierDependency)
    val (resolvedArtifacts, errors) = getAllDependencies(coursierDeps)
    SbtixDebug.info(logger)(s"[SBTIX_DEBUG] Total resolved artifacts: ${resolvedArtifacts.size}")
    if (SbtixDebug.enabled) {
      resolvedArtifacts.foreach { case (_, artifact) =>
        if (artifact.url.startsWith("file:")) {
          logger.info(s"[SBTIX_DEBUG] Resolved local artifact ${artifact.url}")
        } else {
          logger.info(s"[SBTIX_DEBUG] Resolved artifact ${artifact.url}")
        }
      }
    }
    val cleanedErrors = filterLocalResolutionErrors(errors)
    SbtixDebug.info(logger) {
      s"[SBTIX_DEBUG] Resolution errors before filtering: ${errors.errors.size}, after: ${cleanedErrors.errors.size}"
    }

    val repoDescriptors = buildRepoDescriptors(effectiveResolvers)
    val nixRepos = repoDescriptors.map(_.repo).toSet

    val (lockedArtifacts, providedArtifacts) =
      resolvedArtifacts.foldLeft((Set.empty[NixArtifact], Set.empty[ProvidedArtifact])) {
        case ((lockedAcc, providedAcc), (dependency, artifact)) =>
          if (dependency.module.organization.value.contains("sbtix-test-multibuild")) {
            SbtixDebug.info(logger) {
              s"[SBTIX_DEBUG] Evaluating ${dependency.module.organization.value}:${dependency.module.name.value}:${dependency.version} from ${artifact.url}"
            }
          }
          if (artifact.url.startsWith("file:") && artifact.url.contains("sbtix-test-multibuild")) {
            SbtixDebug.info(logger) {
              s"[SBTIX_DEBUG] Local candidate ${artifact.url} -> ${isLocalIvyUrl(artifact.url)}"
            }
          }
          if (isLocalIvyUrl(artifact.url)) {
            SbtixDebug.info(logger) {
              s"[SBTIX_DEBUG] Treating ${dependency.module.organization.value}:${dependency.module.name.value}:${dependency.version} as provided from ${artifact.url}"
            }
            (lockedAcc, providedAcc + ProvidedArtifact.from(dependency, artifact.url))
          } else {
            val expanded = fetchAndExpand(artifact, repoDescriptors, dependency)
            (lockedAcc ++ expanded, providedAcc)
          }
      }

    val resolvedUrls = resolvedArtifacts.map { case (_, artifact) => artifact.url }.toSet

    val metaArtifacts = metaArtifactCollector.asScala.toSet
      .filterNot(meta => resolvedUrls.contains(meta.artifactUrl))
      .filterNot(meta => isLocalIvyUrl(meta.artifactUrl))
      .flatMap { meta =>
        val descriptor = descriptorForUrl(meta.artifactUrl, repoDescriptors)
        val relative = computeRelativePath(meta.artifactUrl, descriptor)
        expandCrossSuffixed(NixArtifact(descriptor.name, relative, meta.artifactUrl, meta.checkSum))
      }

    val sanitizedArtifacts = lockedArtifacts ++ metaArtifacts
    SbtixDebug.info(logger) {
      s"[SBTIX_DEBUG] Locked artifacts: ${lockedArtifacts.size}, meta: ${metaArtifacts.size}, provided: ${providedArtifacts.size}"
    }

    (nixRepos, sanitizedArtifacts, providedArtifacts, Set(cleanedErrors))
  }

  private def toCoursierDependency(dep: SbtixDependency): CDependency = {
    val modId = dep.moduleId
    val resolvedName = resolveModuleName(modId)

    val module = CModule(
      organization = Organization(modId.organization),
      name = ModuleName(resolvedName),
      attributes = modId.extraAttributes
    )

    val exclusions = modId.exclusions.map { rule =>
      (Organization(rule.organization), ModuleName(rule.name))
    }.toSet

    val configuration = modId.configurations.map(Configuration(_)).getOrElse(Configuration.default)

    val publication = modId.explicitArtifacts.headOption.map { art =>
      Publication(
        name = art.name,
        `type` = Type(art.`type`),
        ext = Extension(art.extension),
        classifier = art.classifier.map(Classifier(_)).getOrElse(Classifier.empty)
      )
    }.getOrElse(Publication(modId.name, Type.jar, Extension.jar, Classifier.empty))

    CDependency(
      module = module,
      version = modId.revision,
      configuration = configuration,
      exclusions = exclusions,
      publication = publication,
      optional = false,
      transitive = modId.isTransitive
    )
  }

  /** Most modules follow the standard Scala cross-versioning scheme, so we let
    * sbt’s `CrossVersion` decorate the artifact name (foo_2.13 etc.). sbt
    * plugins, however, encode both Scala and sbt binary versions in the name
    * (foo_2.12_1.0). Those plugins advertise their target sbt/Scala versions via
    * `e:sbtVersion` / `e:scalaVersion` extra attributes, so we look for those
    * keys and synthesize the correct suffix ourselves. This fixes lookup URLs
    * for artifacts like `com.github.sbt:sbt-native-packager`.
    */
  private def resolveModuleName(modId: ModuleID): String = {
    val hasSbtVersionAttr =
      modId.extraAttributes.contains("sbtVersion") || modId.extraAttributes.contains("e:sbtVersion")
    if (hasSbtVersionAttr) {
        // Resolver.sbtPluginRepo already encodes the scala/sbt binary versions
        // in its Ivy pattern; keeping the canonical module name ensures URLs
        // match what Artifactory serves.
        modId.name
    } else {
        CrossVersion(modId.crossVersion, scalaVersion, scalaBinaryVersion)
          .map(applyFn => applyFn(modId.name))
          .getOrElse(modId.name)
    }
  }

  /** sbt plugins are published in both Ivy (unsuffixed module name with
    * scala/sbt directories) and Maven (cross-suffixed module name) layouts. Emit
    * both shapes so resolution can succeed regardless of repository layout.
    */
  private def expandSbtPluginDependency(dep: SbtixDependency): Set[SbtixDependency] = {
    val mod = dep.moduleId
    val isSbtPlugin = mod.extraAttributes.keySet.exists(sbtVersionKeys.contains)
    if (!isSbtPlugin) Set(dep)
    else {
      val crossVariant =
        sbtBinaryVersionAttr.flatMap { sbtBin =>
          val crossName = s"${mod.name}_${scalaBinaryVersion}_${sbtBin}"
          if (crossName == mod.name) None else Some(dep.copy(moduleId = mod.withName(crossName)))
        }
      Set(dep) ++ crossVariant.toSet
    }
  }

  private def downloadArtifact(artifact: CArtifact): Option[File] =
    FileCache[Task](location = CacheDefaults.location)
      .file(artifact)
      .run
      .unsafeRun()
      .toOption

  private def buildRepoDescriptors(sbtResolvers: Set[Resolver]): Seq[RepoDescriptor] = {
    val baseDescriptors = sbtResolvers.flatMap { resolver =>
      extractRoot(resolver).flatMap { root =>
        val resolvedRoot = resolveIvyVariables(root)
        val normalizedRoot = normalizeRoot(resolvedRoot)
        if (isIvyLocalRoot(normalizedRoot)) None
        else {
          val repoName = determineRepoName(resolver.name, normalizedRoot)
          val pattern = extractPattern(resolver).map(resolveIvyVariables).getOrElse("")
          Some(RepoDescriptor(repoName, normalizedRoot, NixRepo(repoName, pattern)))
        }
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

  private def resolverToCoursierRepository(resolver: Resolver): Option[Repository] = {
    val repoOpt = Resolvers.repository(
      resolver = resolver,
      ivyProperties = ivyProps,
      log = logger,
      authentication = authenticationFor(resolver),
      classLoaders = Seq()
    )

    /* lmcoursier 2.1.x now returns shaded repository implementations; bridge them back into
     * public coursier.core.Repository so we can keep using the new API surface.
     */
    repoOpt.map {
      case repo: Repository => repo
      case other            => convertRepository(other)
    }
  }

  private def buildFetcherRepositories(): Seq[Repository] =
    effectiveResolvers.flatMap(resolverToCoursierRepository).toSeq

  private def buildNixProject(modules: Set[CDependency]): (Set[(CDependency, CArtifact)], ResolutionErrors) =
    getAllDependencies(modules)

  private def getAllDependencies(modules: Set[CDependency]): (Set[(CDependency, CArtifact)], ResolutionErrors) = {
    val repos = buildFetcherRepositories()
    /* Coursier 2.1.24 switched to ResolutionProcess.fetch instead of the older Fetch.from API;
     * using the new entry point keeps parity with upstream's parallel fetch/back-pressure improvements.
     */
    val fetch = ResolutionProcess.fetch(repos, CacheFetch_WithCollector())

    def go(deps: Set[CDependency]): (Set[(CDependency, CArtifact)], ResolutionErrors) = {
      val configured = Resolution()
        .withRootDependencies(deps.toSeq)
        .withDefaultConfiguration(Configuration.defaultCompile)
        /* Keep dependency overrides disabled so the upgraded coursier behaves like the
         * legacy master implementation.
         */
        .withEnableDependencyOverrides(false)

      val resolution = configured.process.run(fetch, maxIterations = 100).unsafeRun()

      val missingDependencies = findMissingDependencies(deps, resolution)
      val resolvedMissingDependencies = missingDependencies.map(md => go(Set(md)))

      val mainArtifacts = resolution.dependencyArtifacts().toSet
      val classifierArtifacts =
        resolution.dependencyArtifacts(classifiers = Some(Seq("tests", "sources", "javadoc").map(Classifier(_)))).toSet

      val artifacts =
        mainArtifacts
          .union(classifierArtifacts)
          .map { case (dependency, _, artifact) => (dependency, artifact) }
          .union(resolvedMissingDependencies.flatMap(_._1))

      val errors =
        resolvedMissingDependencies.foldRight(
          ResolutionErrors(resolution.errors)
        )((resolved, acc) => acc + resolved._2)

      (artifacts, errors)
    }

    go(modules)
  }

  private def findMissingDependencies(dependencies: Set[CDependency], resolution: Resolution): Set[CDependency] =
    dependencies.flatMap { dep =>
      if (resolution.dependencies.contains(dep))
        findMissingDependencies(dep, resolution)
      else
        Set(dep)
    }

  private def findMissingDependencies(module: CDependency, resolution: Resolution): Set[CDependency] = {

    def getDeps(dep: CDependency, withReconciledVersions: Boolean, maxDepth: Int): RoseTree[CDependency] =
      if (maxDepth == 0)
        RoseTree(dep, Nil)
      else {
        val children = resolution
          .dependenciesOf(dep, withReconciledVersions)
          .toList
          .map(child => getDeps(child, withReconciledVersions, maxDepth - 1))
        RoseTree(dep, children)
      }

    val reconciled = getDeps(module, withReconciledVersions = true, 100)
    val raw = getDeps(module, withReconciledVersions = false, 100)

    val possiblyMissing = diffDependencyTrees(raw, reconciled)
    possiblyMissing.diff(resolution.dependencies)
  }

  private def diffDependencyTrees(raw: RoseTree[CDependency], reconciled: RoseTree[CDependency]): Set[CDependency] = {
    val rawMap = raw.children.map(t => t.value -> t).toMap
    val recMap = reconciled.children.map(t => t.value -> t).toMap

    val diff = rawMap.keySet.diff(recMap.keySet)
    val intersection = rawMap.keySet.intersect(recMap.keySet)

    diff ++ intersection.flatMap { dep =>
      diffDependencyTrees(rawMap(dep), recMap(dep))
    }
  }

  private def CacheFetch_WithCollector(
    location: File = CacheDefaults.location,
    cachePolicies: Seq[CachePolicy] = Seq(CachePolicy.FetchMissing),
    checksums: Seq[Option[String]] = CacheDefaults.checksums,
    cacheLogger: CacheLogger = CacheLogger.nop,
    pool: ExecutorService = CacheDefaults.pool,
    ttl: Option[Duration] = CacheDefaults.ttl
  ): Cache.Fetch[Task] = { artifact =>
    /* FileCache gained new defaults in 2.1.x; set the knobs explicitly so fetches stay
     * deterministic across the upgraded API surface.
     */
    val fileCache = FileCache[Task](
      location = location,
      cachePolicies = cachePolicies,
      checksums = checksums,
      credentials = CacheDefaults.credentials,
      logger = cacheLogger,
      pool = pool,
      ttl = ttl,
      localArtifactsShouldBeCached = false,
      followHttpToHttpsRedirections = true,
      followHttpsToHttpRedirections = false,
      maxRedirections = CacheDefaults.maxRedirections,
      sslRetry = 3,
      sslSocketFactoryOpt = None,
      hostnameVerifierOpt = None,
      retry = CacheDefaults.defaultRetryCount,
      bufferSize = CacheDefaults.bufferSize,
      classLoaders = Nil
    )

    fileCache
      .file(artifact)
      .leftMap(_.describe)
      .flatMap { f =>
        def notFound(file: File) = Left(s"${file.getCanonicalPath} not found")

        def read(file: File) =
          try Right(
            new String(java.nio.file.Files.readAllBytes(file.toPath), "UTF-8")
              .stripPrefix("\ufeff")
          )
          catch {
            case NonFatal(e) =>
              Left(s"Could not read (file:${file.getCanonicalPath}): ${e.getMessage}")
          }

        val res =
          if (f.exists()) {
            if (f.isDirectory) {
              if (artifact.url.startsWith("file:")) {
                val elements = f.listFiles.map { c =>
                  val name = c.getName
                  val name0 = if (c.isDirectory) name + "/" else name
                  s"""<li><a href="$name0">$name0</a></li>"""
                }.mkString

                val page =
                  s"""<!DOCTYPE html>
                     |<html>
                     |<head></head>
                     |<body>
                     |<ul>
                     |$elements
                     |</ul>
                     |</body>
                     |</html>
                   """.stripMargin

                Right(page)
              } else {
                val directoryFile = new File(f, ".directory")
                if (directoryFile.exists()) {
                  if (directoryFile.isDirectory) Left(s"Woops: ${f.getCanonicalPath} is a directory")
                  else read(directoryFile)
                } else notFound(directoryFile)
              }
            } else read(f)
          } else notFound(f)

        if (res.isRight && artifact.url.startsWith("http")) {
          val checkSum =
            FindArtifactsOfRepo
              .fetchChecksum(artifact.url, "-Meta- Artifact", f.toURI.toURL)
              .get

          val authedUrl = artifact.authentication match {
            case Some(auth) if auth.passwordOpt.isDefined =>
              val parts = artifact.url.split("://")
              require(parts.length == 2, s"URL should be splittable: ${artifact.url}")
              parts(0) + "://" + auth.user + ":" + auth.passwordOpt.get + "@" + parts(1)
            case _ => artifact.url
          }
          metaArtifactCollector.add(MetaArtifact(authedUrl, checkSum))
        }

        EitherT.fromEither(res)
      }
  }

  private case class RepoDescriptor(name: String, normalizedRoot: String, repo: NixRepo)

  private val DefaultPublicRoot = normalizeRoot("https://repo1.maven.org/maven2/")

  private def sanitizeRepoName(raw: String): String =
    raw.trim.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-")

  private def stripTrailingSlash(value: String): String =
    if (value.endsWith("/")) value.dropRight(1) else value

  private def ensureTrailingSlash(url: String): String =
    if (url.endsWith("/")) url else url + "/"

  private def stripCredentials(url: String): String = {
    val credentialPattern = "^(https?://)([^@/]+@)(.*)$".r
    url match {
      case credentialPattern(prefix, _, rest) => prefix + rest
      case _ => url
    }
  }

  private def normalizeRoot(url: String): String = {
    val trimmed = stripCredentials(url.trim)
    val withScheme =
      if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file:"))
        trimmed
      else
        new File(trimmed).toURI.toString
    ensureTrailingSlash(withScheme)
  }

  private def fallbackNameFromRoot(root: String): String = {
    val withoutScheme = root.replaceFirst("https?://", "")
    val cleaned = sanitizeRepoName(withoutScheme)
    if (cleaned.nonEmpty) cleaned else "nix-public"
  }

  private def determineRepoName(raw: String, normalizedRoot: String): String = {
    if (normalizedRoot == DefaultPublicRoot) {
      "nix-public"
    } else {
      val cleaned = sanitizeRepoName(raw)
      val base = if (cleaned.nonEmpty) cleaned else fallbackNameFromRoot(normalizedRoot)
      if (base.startsWith("nix-")) base else s"nix-$base"
    }
  }

  private def extractRoot(resolver: Resolver): Option[String] = resolver match {
    case m: MavenRepository => Some(m.root)
    case p: PatternsBasedRepository if p.patterns.artifactPatterns.nonEmpty =>
      p.patterns.artifactPatterns.headOption.flatMap { pattern =>
        val idx = pattern.indexOf('[')
        val base = if (idx > 0) pattern.substring(0, idx) else pattern
        Option(base).filter(_.nonEmpty)
      }
    case u: URLRepository if u.patterns.artifactPatterns.nonEmpty =>
      u.patterns.artifactPatterns.headOption.flatMap { pattern =>
        val idx = pattern.indexOf('[')
        val base = if (idx > 0) pattern.substring(0, idx) else pattern
        Option(base).filter(_.nonEmpty)
      }
    case _ => None
  }

  /** Extract the repository-relative Ivy pattern from a resolver (everything from the first
    * '[' onwards). This is what sbt expects in `repositories` config entries for Ivy-style repos.
    *
    * Example:
    *   "https://repo.scala-sbt.org/.../sbt-plugin-releases/[organisation]/[module]/.../ivy.xml"
    * becomes:
    *   "[organisation]/[module]/.../ivy.xml"
    */
  private def extractPattern(resolver: Resolver): Option[String] = resolver match {
    case _: MavenRepository => None
    case p: PatternsBasedRepository if p.patterns.artifactPatterns.nonEmpty =>
      p.patterns.artifactPatterns.headOption.flatMap { full =>
        val idx = full.indexOf('[')
        if (idx >= 0 && idx < full.length) Option(full.substring(idx)).filter(_.nonEmpty)
        else None
      }
    case u: URLRepository if u.patterns.artifactPatterns.nonEmpty =>
      u.patterns.artifactPatterns.headOption.flatMap { full =>
        val idx = full.indexOf('[')
        if (idx >= 0 && idx < full.length) Option(full.substring(idx)).filter(_.nonEmpty)
        else None
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

  /** Some Maven-hosted sbt plugins live under a cross-versioned directory name but
    * publish files named without the cross suffix (e.g. artifactId `_2.12_1.0`, file `foo-1.0.pom`).
    * Keep both shapes to satisfy the sbt launcher in offline mode.
    */
  private def expandCrossSuffixed(artifact: NixArtifact): Set[NixArtifact] = {
    val rel = artifact.relativePath
    val idx = rel.lastIndexOf('/')
    if (idx < 0) Set(artifact)
    else {
      val (dir, file) = rel.splitAt(idx + 1)
      val CrossFile = "(.+?)_\\d+\\.\\d+(?:_\\d+\\.\\d+)?-(.+)".r
      file match {
        case CrossFile(baseWithSuffix, rest) =>
          val baseNoSuffix = baseWithSuffix.replaceAll("_[0-9]+\\.[0-9]+(_[0-9]+\\.[0-9]+)?$", "")
          val altFile = dir + s"$baseNoSuffix-$rest"

          // Also emit a variant where the directory segment drops the cross suffix, matching
          // how sbt resolves some plugin artifacts in Maven layout.
          val dirAlt =
            if (dir.endsWith(s"$baseWithSuffix/"))
              dir.dropRight(baseWithSuffix.length + 1) + baseNoSuffix + "/"
            else dir
          val altDirFile = dirAlt + s"$baseNoSuffix-$rest"

          Set(
            artifact,
            artifact.copy(relativePath = altFile),
            artifact.copy(relativePath = altDirFile)
          )
        case _ =>
          // If only the directory carries the cross suffix (common for Maven layout of sbt plugins),
          // emit a variant with the directory suffix stripped.
          val DirWithSuffix = "(.*\\/)?(.+?)_\\d+\\.\\d+(?:_\\d+\\.\\d+)?\\/$".r
          dir match {
            case DirWithSuffix(prefix, baseWithSuffix) =>
              val baseNoSuffix = baseWithSuffix.replaceAll("_[0-9]+\\.[0-9]+(_[0-9]+\\.[0-9]+)?$", "")
              val altDir = Option(prefix).getOrElse("") + baseNoSuffix + "/"
              Set(artifact, artifact.copy(relativePath = altDir + file))
            case _ => Set(artifact)
          }
      }
    }
  }

  /** Download an artifact, emit its NixArtifact(s), and for POMs also emit parent and import-scope BOMs. */
  private def fetchAndExpand(artifact: CArtifact, repoDescriptors: Seq[RepoDescriptor], dep: CDependency): Set[NixArtifact] = {
    val base = downloadArtifact(artifact).map { file =>
      val descriptor = descriptorForUrl(artifact.url, repoDescriptors)
      val relative = computeRelativePath(artifact.url, descriptor)
      val sha256 = computeSha256(file)
      expandCrossSuffixed(NixArtifact(descriptor.name, relative, artifact.url, sha256))
    }.getOrElse(Set.empty)

    val pomExtras =
      if (artifact.url.endsWith(".pom"))
        collectPomAncestors(artifact, repoDescriptors)
      else Set.empty[NixArtifact]

    base ++ pomExtras
  }

  /** Parse a POM and lock any parent POM and imported BOMs (scope=import, type=pom). */
  private def collectPomAncestors(artifact: CArtifact, repoDescriptors: Seq[RepoDescriptor]): Set[NixArtifact] = {
    downloadArtifact(artifact).map { file =>
      val pom = PomModel.parse(file)
      val candidates = pom.parents ++ pom.importBoms ++ pom.optionalDeps
      candidates.flatMap { pomDep =>
        val path = s"${pomDep.groupId.replace('.', '/')}/${pomDep.artifactId}/${pomDep.version}/${pomDep.artifactId}-${pomDep.version}.${if (pomDep.`type`.nonEmpty) pomDep.`type` else "pom"}"
        val descriptor = repoDescriptors.find(_.normalizedRoot == DefaultPublicRoot).getOrElse(repoDescriptors.head)
        val url = descriptor.normalizedRoot + path
        downloadArtifact(CArtifact(url)).toSeq.flatMap { f =>
          val relative = computeRelativePath(url, descriptor)
          val sha256 = computeSha256(f)
          expandCrossSuffixed(NixArtifact(descriptor.name, relative, url, sha256))
        }
      }.toSet
    }.getOrElse(Set.empty)
  }

  private val ivyVariablePattern = "\\$\\{([^}]+)\\}".r

  private def resolveIvyVariables(input: String): String =
    ivyVariablePattern.replaceAllIn(input, m => ivyProps.getOrElse(m.group(1), m.matched))

  private def computeSha256(file: File): String =
    NixArtifact.checksum(file)

  private def authenticationFor(resolver: Resolver): Option[Authentication] = resolver match {
    case m: MavenRepository =>
      hostFromUrl(m.root).flatMap(credentialsForHost)
    case p: PatternsBasedRepository if p.patterns.artifactPatterns.nonEmpty =>
      p.patterns.artifactPatterns.headOption
        .flatMap(hostFromUrl)
        .flatMap(credentialsForHost)
    case _ => None
  }

  private def hostFromUrl(url: String): Option[String] =
    Try(new URI(url)).toOption.flatMap(uri => Option(uri.getHost))

  private def credentialsForHost(host: String): Option[Authentication] =
    Credentials.forHost(credentials.toSeq, host).map { c =>
      Authentication(
        c.userName,
        c.passwd,
        optional = true,
        realmOpt = Option(c.realm),
        httpsOnly = false,
        passOnRedirect = false
      )
    }

  private def ivyProps: Map[String, String] =
    Map("ivy.home" -> new File(sys.props("user.home"), ".ivy2").toString) ++ sys.props ++ extraIvyProps

  private val ivyLocalDir = new File(new File(sys.props("user.home"), ".ivy2"), "local")
  private val ivyLocalCanonical = ivyLocalDir.getCanonicalFile
  private val ivyLocalUriPrefix = ivyLocalCanonical.toURI.toString.stripSuffix("/")

  private def isLocalIvyUrl(url: String): Boolean =
    if (!url.startsWith("file:")) false
    else {
      try {
        val uri = new URI(url)
        val canonical = new File(uri).getCanonicalFile
        val basePath = ivyLocalCanonical.getPath
        val canonicalPath = canonical.getPath
        val result = canonicalPath.startsWith(basePath)
        if (!result) {
          SbtixDebug.info(logger) {
            s"[SBTIX_DEBUG] Local Ivy check mismatch: candidate=$canonicalPath base=$basePath for url=$url"
          }
        } else {
          SbtixDebug.info(logger) {
            s"[SBTIX_DEBUG] Classified local Ivy artifact via canonical path $canonicalPath"
          }
        }
        if (result) {
          SbtixDebug.info(logger) {
            s"[SBTIX_DEBUG] Classified local Ivy artifact via canonical path ${canonical.getPath}"
          }
        }
        result
      } catch {
        case e: Exception =>
          SbtixDebug.warn(logger)(s"[SBTIX_DEBUG] Failed to inspect local Ivy URL $url: ${e.getMessage}")
          false
      }
    }

  private def isIvyLocalRoot(root: String): Boolean =
    try {
      val uri = new URI(root)
      Option(uri.getScheme).exists(_.equalsIgnoreCase("file")) &&
      new File(uri).getCanonicalPath.startsWith(ivyLocalCanonical.getPath)
    } catch {
      case _: Exception => false
    }

  private def filterLocalResolutionErrors(errors: ResolutionErrors): ResolutionErrors = {
    val (skipped, kept) = errors.errors.partition { case ((module, version), _) =>
      val moduleDir = new File(ivyLocalDir, s"${module.organization.value}/${module.name.value}/$version")
      val exists = moduleDir.exists()
      SbtixDebug.info(logger) {
        s"[SBTIX_DEBUG] Checking Ivy local for ${module.organization.value}:${module.name.value}:$version at ${moduleDir.getAbsolutePath} -> ${if (exists) "found" else "missing"}"
      }
      exists
    }

    if (skipped.nonEmpty) {
      val modules = skipped.map { case ((module, version), _) => s"${module.organization.value}:${module.name.value}:$version" }
      SbtixDebug.info(logger) {
        s"[SBTIX_DEBUG] Treating ${modules.mkString(", ")} as provided by sbtix-build-inputs (found in Ivy local)."
      }
    }

    ResolutionErrors(kept)
  }
}

case class ResolutionErrors(errors: Seq[(Resolution.ModuleVersion, Seq[String])]) {
  def +(other: ResolutionErrors): ResolutionErrors =
    ResolutionErrors(errors ++ other.errors)

  def +(others: Seq[ResolutionErrors]): ResolutionErrors =
    ResolutionErrors(errors ++ others.flatMap(_.errors))
}
