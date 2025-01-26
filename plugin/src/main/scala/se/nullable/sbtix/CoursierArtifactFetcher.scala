package se.nullable.sbtix

import scala.language.postfixOps
import scala.language.implicitConversions

import java.io.File
import java.net.{URI, URL}
import sbt.{uri, Credentials, Logger, ModuleID, PatternsBasedRepository, ProjectRef, Resolver}
import coursier.*
import lmcoursier.internal.shaded.coursier.core.Authentication
import coursier.util.*
import coursier.cache.{Cache, CacheDefaults, CacheLogger, CachePolicy, FileCache}
import coursier.util.EitherT
import lmcoursier.internal.Resolvers
import lmcoursier.FromSbt

import java.util.concurrent.ConcurrentSkipListSet
import scala.collection.JavaConverters.*
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import java.nio.file.{StandardCopyOption, Files => NioFiles}
import se.nullable.sbtix.data.RoseTree
import se.nullable.sbtix.utils.Conversions.*

case class GenericModule(primaryArtifact: Artifact, dep: Dependency, localFile: File) {
  private val isIvy = localFile.getParentFile.getName == "jars"

  val url = URI.create(primaryArtifact.url).toURL()

  private val authedUri = authed(url)

  /** remote location of the module and all related artifacts */
  private val calculatedParentURI =
    if (isIvy)
      parentURI(parentURI(authedUri))
    else
      parentURI(authedUri)

  /** create the authenticated version of a given url */
  def authed(url: URL): URI =
    primaryArtifact.authentication match {
      case Some(a) =>
        a.passwordOpt match {
          case Some(pwd) =>
            new URI(
              url.getProtocol,
              s"${a.user}:${pwd}",
              url.getHost,
              url.getPort,
              url.getPath,
              url.getQuery,
              url.getRef
            )
          case None => url.toURI
        }
      case None => url.toURI
    }

  /**
   * resolve the URI of a sibling artifact, based on the primary artifact's
   * parent URI
   */
  def calculateURI(f: File): URI =
    if (isIvy)
      calculatedParentURI.resolve(f.getParentFile.getName + "/" + f.getName)
    else
      calculatedParentURI.resolve(f.getName)

  /** local location of the module and all related artifacts */
  val localSearchLocation =
    if (isIvy)
      localFile.getParentFile.getParentFile
    else
      localFile.getParentFile
}

case class MetaArtifact(artifactUrl: String, checkSum: String) extends Comparable[MetaArtifact] {
  override def compareTo(other: MetaArtifact): Int =
    artifactUrl.compareTo(other.artifactUrl)

  def matchesGenericModule(gm: GenericModule): Boolean = {
    val organ   = gm.dep.module.organization
    val name    = gm.dep.module.name
    val version = gm.dep.version

    val slashOrgans = organ.copy(value = organ.value.replace(".", "/"))

    val mvn = s"$slashOrgans/$name/$version"
    val ivy = s"$organ/$name/$version"

    artifactUrl.contains(mvn) || artifactUrl.contains(ivy)
  }
}

class CoursierArtifactFetcher(
  logger: Logger,
  resolvers: Set[Resolver],
  credentials: Set[Credentials]
) {

  // Collects pom.xml and ivy.xml URLs from Coursier internals
  val metaArtifactCollector = new ConcurrentSkipListSet[MetaArtifact]()

  def apply(depends: Set[Dependency]): (Set[NixRepo], Set[NixArtifact], Set[ResolutionErrors]) = {
    val (mods, errors) = buildNixProject(depends)

    // Remove metaArtifacts that already have a module, to avoid double-processing
    val metaArtifacts =
      metaArtifactCollector.asScala.toSet.filterNot(meta => mods.exists(meta.matchesGenericModule))

    // object to work with the rootUrl of Resolvers
    val nixResolver = resolvers.map(NixResolver.resolve)

    // retrieve artifacts: poms/ivys/jars
    val (repoSeq, artifactsSeqSeq) =
      nixResolver.flatMap(_.filterArtifacts(logger, mods)).unzip

    // retrieve metaArtifacts that were missed (e.g. parent POMs)
    val (metaRepoSeq, metaArtifactsSeqSeq) =
      nixResolver.flatMap(_.filterMetaArtifacts(logger, metaArtifacts)).unzip

    val nixArtifacts = artifactsSeqSeq.flatten ++ metaArtifactsSeqSeq.flatten
    val nixRepos     = repoSeq ++ metaRepoSeq

    (nixRepos, nixArtifacts, Set(errors))
  }

  /**
   * Custom fetch to collect POM/ivy metadata for the metaArtifactCollector
   */
  def CacheFetch_WithCollector(
    location: File = CacheDefaults.location,
    cachePolicies: Seq[CachePolicy] = Seq(CachePolicy.FetchMissing),
    checksums: Seq[Option[String]] = CacheDefaults.checksums,
    logger: CacheLogger = CacheLogger.nop,
    pool: ExecutorService = CacheDefaults.pool,
    ttl: Option[Duration] = CacheDefaults.ttl
  ): Cache.Fetch[Task] = { artifact =>
    val fileCache = FileCache[Task](
      location = location,
      cachePolicies = cachePolicies,
      checksums = checksums,
      credentials = CacheDefaults.credentials,
      logger = logger,
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
        def notFound(f: File) = Left(s"${f.getCanonicalPath} not found")

        def read(file: File) =
          try Right(
            new String(NioFiles.readAllBytes(file.toPath), "UTF-8")
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
                  val name0 =
                    if (c.isDirectory) name + "/"
                    else name
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
                val f0 = new File(f, ".directory")
                if (f0.exists()) {
                  if (f0.isDirectory) Left(s"Woops: ${f.getCanonicalPath} is a directory")
                  else read(f0)
                } else notFound(f0)
              }
            } else read(f)
          } else notFound(f)

        // If res is Right, artifact was successfully downloaded
        if (res.isRight && artifact.url.startsWith("http")) {
          // reduce the number of "failed" metaArtifacts by checking success
          val checkSum =
            FindArtifactsOfRepo
              .fetchChecksum(artifact.url, "-Meta- Artifact", f.toURI.toURL)
              .get // caution: .get can throw if None

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

  private def buildNixProject(modules: Set[Dependency]): (Set[GenericModule], ResolutionErrors) = {
    val (dependenciesArtifacts, errors) = getAllDependencies(modules)
    val genericModules = dependenciesArtifacts.flatMap { case (dependency, artifact) =>
      val downloadedArtifact =
        FileCache[Task](location = CacheDefaults.location).file(artifact)

      downloadedArtifact.run.unsafeRun().toOption.map { localFile =>
        GenericModule(artifact, dependency, localFile)
      }
    }
    (genericModules, errors)
  }

  private def getAllDependencies(modules: Set[Dependency]): (Set[(Dependency, Artifact)], ResolutionErrors) = {

    // Convert sbt Resolvers to coursier Repositories
    val repos = resolvers.flatMap { resolver =>
      val maybeAuth: Option[Authentication] = resolver match {
        case mvn: sbt.MavenRepo =>
          Credentials.forHost(credentials.toSeq, sbt.url(mvn.root).getHost).map { c =>
            Authentication(
              c.userName,
              c.passwd,
              optional = true,
              Some(c.realm),
              httpsOnly = false,
              passOnRedirect = false
            )
          }
        case ivy: PatternsBasedRepository =>
          val pat      = ivy.patterns.artifactPatterns.head
          val endIndex = pat.indexOf("[")
          val root     = pat.substring(0, endIndex)
          Credentials.forHost(credentials.toSeq, uri(root).getHost).map { c =>
            Authentication(
              c.userName,
              c.passwd,
              optional = true,
              Some(c.realm),
              httpsOnly = false,
              passOnRedirect = false
            )
          }
        case _: sbt.MavenCache =>
          None // local maven cache typically doesn't need credentials
        case other =>
          throw new IllegalStateException(
            s"Determining credentials for $other of type ${other.getClass} is not yet implemented"
          )
      }

      val repo: Option[Any] = Resolvers.repository(
        resolver = resolver,
        ivyProperties = ivyProps,
        log = logger,
        authentication = maybeAuth,
        classLoaders = Seq()
      )
      repo.map {
        case coreRepo: Repository =>
          coreRepo
        case otherRepo =>
          // From the signature on `Resolvers.repository` you would expect this to be dead code,
          // but depending on the classpath `repository` may return a shaded Repository object
          // that needs to be converted:
          convertRepository(otherRepo)
      }
    }

    val fetch = ResolutionProcess.fetch(repos.toSeq, CacheFetch_WithCollector())

    def go(deps: Set[Dependency]): (Set[(Dependency, Artifact)], ResolutionErrors) = {
      val res        = Resolution(deps.toSeq)
      val resolution = res.process.run(fetch, maxIterations = 100).unsafeRun()

      val missingDependencies = findMissingDependencies(deps, resolution)
      val resolvedMissingDependencies =
        missingDependencies.map(md => go(Set(md)))

      val mainArtifacts =
        resolution
          .dependencyArtifacts()
          .toSet

      // Still fetch extra classifiers, e.g. "tests", "sources", "javadoc"
      val classifierArtifacts =
        resolution
          .dependencyArtifacts(classifiers = Some(Seq("tests", "sources", "javadoc").map(Classifier(_))))
          .toSet

      val artifacts =
        mainArtifacts
          .union(classifierArtifacts)
          .map { case (dependency, publication, artifact) => (dependency, artifact) }
          .union(resolvedMissingDependencies.flatMap(_._1))

      val errors =
        resolvedMissingDependencies.foldRight(
          ResolutionErrors(resolution.errors)
        )((resolved, acc) => acc + resolved._2)

      (artifacts, errors)
    }

    go(modules)
  }

  /**
   * If a given dependency wasn't in resolution.dependencies, try to see if
   * there's a reconciled version or forced version we need to walk.
   */
  private def findMissingDependencies(dependencies: Set[Dependency], resolution: Resolution): Set[Dependency] =
    dependencies.flatMap { dep =>
      if (resolution.dependencies.contains(dep))
        findMissingDependencies(dep, resolution)
      else
        Set(dep)
    }

  private def findMissingDependencies(module: Dependency, resolution: Resolution): Set[Dependency] = {

    def getDeps(dep: Dependency, withReconciledVersions: Boolean, maxDepth: Int): RoseTree[Dependency] =
      if (maxDepth == 0)
        RoseTree(dep, Nil)
      else {
        val children = resolution
          .dependenciesOf(dep, withReconciledVersions)
          .toList
          .map(child => getDeps(child, withReconciledVersions, maxDepth - 1))
        RoseTree(dep, children)
      }

    // Reconciled version tree
    val reconciled = getDeps(module, withReconciledVersions = true, 100)
    // Raw version tree
    val raw = getDeps(module, withReconciledVersions = false, 100)

    // Those in raw but not in reconciled might have been "dropped"
    val possiblyMissing = diffDependencyTrees(raw, reconciled)
    possiblyMissing.diff(resolution.dependencies)
  }

  private def diffDependencyTrees(raw: RoseTree[Dependency], reconciled: RoseTree[Dependency]): Set[Dependency] = {
    val rawMap =
      raw.children.map { t =>
        t.value -> t
      }.toMap
    val recMap =
      reconciled.children.map { t =>
        t.value -> t
      }.toMap

    val diff =
      rawMap.keySet
        .diff(recMap.keySet)

    val intersection =
      rawMap.keySet
        .intersect(recMap.keySet)

    diff ++ intersection.flatMap { dep =>
      diffDependencyTrees(rawMap(dep), recMap(dep))
    }
  }

  private def ivyProps: Map[String, String] =
    Map("ivy.home" -> new File(sys.props("user.home"), ".ivy2").toString) ++ sys.props
}

case class ResolutionErrors(errors: Seq[(ModuleVersion, Seq[String])]) {
  def +(other: ResolutionErrors): ResolutionErrors =
    ResolutionErrors(errors ++ other.errors)

  def +(others: Seq[ResolutionErrors]): ResolutionErrors =
    ResolutionErrors(errors ++ others.flatMap(_.errors))
}
