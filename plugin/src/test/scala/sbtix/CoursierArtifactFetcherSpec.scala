package sbtix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{File, PrintWriter}
import se.nullable.sbtix.Utils
import se.nullable.sbtix.{CoursierArtifactFetcher, Dependency, NixArtifact, NixRepo}
import sbt.librarymanagement.{ConfigRef, ConfigurationReport, MavenRepository, ModuleReport, Patterns, ScalaModuleInfo, UpdateReport, UpdateStats}
import sbt.{Logger, ModuleID, Resolver}

class CoursierArtifactFetcherSpec extends AnyFlatSpec with Matchers {

  "Utils.computeSha256" should "correctly calculate SHA-256 hash" in {
    // Create a temporary file with known content
    val tempFile = java.io.File.createTempFile("test", ".txt")
    tempFile.deleteOnExit()
    
    val writer = new java.io.PrintWriter(tempFile)
    writer.write("test content for SHA-256 calculation")
    writer.close()
    
    // Calculate SHA-256 hash
    val hash = Utils.computeSha256(tempFile)
    
    // Test that we get a valid SHA-256 hash
    hash should have length 64
    hash should fullyMatch regex "[0-9a-f]{64}"
  }
  
  val mockLogger = new Logger {
    def trace(t: => Throwable): Unit = ()
    def success(message: => String): Unit = ()
    def log(level: sbt.Level.Value, message: => String): Unit = println(s"[$level] $message")
  }
  
  "CoursierArtifactFetcher" should "work with Coursier" in {
    // Create a simple resolver and dependencies setup
    val resolvers = Set[Resolver](
      MavenRepository("central", "https://repo1.maven.org/maven2")
    )
    
    val dependencies = Set(
      Dependency(
        ModuleID("com.lihaoyi", "upickle_2.12", "4.4.3")
      )
    )
    
    // Create the fetcher
    val fetcher = new CoursierArtifactFetcher(
      mockLogger, 
      resolvers,
      Set.empty,
      "2.12.21",
      "2.12"
    )
    
    // Call the fetcher
    val (repos, artifacts, provided, errors) = fetcher(dependencies)
    
    // Verify the results
    repos should not be empty
    artifacts should not be empty
    errors.flatMap(_.errors) shouldBe empty
    provided shouldBe empty
    artifacts.exists(_.relativePath.contains("-javadoc.jar")) shouldBe false
    
    // Print some debug info
    println(s"Repositories: ${repos.size}")
    println(s"Artifacts: ${artifacts.size}")
    artifacts.take(3).foreach { a =>
      println(s"Artifact: ${a.repoName}/${a.relativePath} - ${a.sha256}")
    }
  }

  it should "include javadoc classifier artifacts when configured" in {
    val resolvers = Set[Resolver](
      MavenRepository("central", "https://repo1.maven.org/maven2")
    )

    val dependencies = Set(
      Dependency(
        ModuleID("com.typesafe", "config", "1.4.9")
      )
    )

    val fetcher = new CoursierArtifactFetcher(
      mockLogger,
      resolvers,
      Set.empty,
      "2.12.21",
      "2.12",
      artifactClassifiers = Seq("javadoc")
    )

    val (_, artifacts, provided, errors) = fetcher(dependencies)

    errors.flatMap(_.errors) shouldBe empty
    provided shouldBe empty
    artifacts.exists(_.relativePath.contains("-javadoc.jar")) shouldBe true
  }

  it should "not lock mutable Maven metadata fetched during version-range resolution" in {
    val resolvers = Set[Resolver](
      MavenRepository("central", "https://repo1.maven.org/maven2")
    )

    val dependencies = Set(
      Dependency(
        ModuleID("org.agrona", "agrona", "[1.22.0,1.23.1]")
      )
    )

    val fetcher = new CoursierArtifactFetcher(
      mockLogger,
      resolvers,
      Set.empty,
      "2.12.21",
      "2.12",
      artifactClassifiers = Seq.empty
    )

    val (_, artifacts, provided, errors) = fetcher(dependencies)
    val metadataArtifacts = artifacts.filter(_.relativePath.contains("maven-metadata.xml"))

    errors.flatMap(_.errors) shouldBe empty
    provided shouldBe empty
    withClue(s"metadata artifacts should not be pinned in repo.nix: $metadataArtifacts") {
      metadataArtifacts shouldBe empty
    }
  }

  it should "lock provided dependency jars discovered from cached POMs" in {
    val resolvers = Set[Resolver](
      MavenRepository("central", "https://repo1.maven.org/maven2")
    )

    val dependencies = Set(
      Dependency(
        ModuleID("org.scala-sbt", "compiler-bridge_2.12", "1.12.0")
      )
    )

    val fetcher = new CoursierArtifactFetcher(
      mockLogger,
      resolvers,
      Set.empty,
      "2.12.21",
      "2.12",
      artifactClassifiers = Seq.empty
    )

    val (_, artifacts, provided, errors) = fetcher(dependencies)

    errors.flatMap(_.errors) shouldBe empty
    provided shouldBe empty
    artifacts.map(_.relativePath) should contain allOf (
      "org/scala-lang/scala-compiler/2.12.20/scala-compiler-2.12.20.jar",
      "org/scala-sbt/util-interface/1.11.5/util-interface-1.11.5.jar"
    )
  }

  it should "lock direct dependency POM metadata discovered from cached POMs" in {
    val resolvers = Set[Resolver](
      MavenRepository("central", "https://repo1.maven.org/maven2")
    )
    val module = ModuleID("net.bzzt", "reproducible-builds-jvm-stripper", "0.10")

    val fetcher = new CoursierArtifactFetcher(
      mockLogger,
      resolvers,
      Set.empty,
      "2.12.21",
      "2.12",
      artifactClassifiers = Seq.empty
    )

    val (_, _, _, seedErrors) = fetcher(Set(Dependency(module)))
    val (_, artifacts) = fetcher.lockModulePoms(Set(module))
    val relativePaths = artifacts.map(_.relativePath)

    seedErrors.flatMap(_.errors) shouldBe empty
    relativePaths should contain("org/codehaus/plexus/plexus-utils/3.4.2/plexus-utils-3.4.2.pom")
    relativePaths should not contain "org/codehaus/plexus/plexus-utils/3.4.2/plexus-utils-3.4.2.jar"
  }

  it should "lock direct dependency jars from cached POMs when building plugin repositories" in {
    val resolvers = Set[Resolver](
      MavenRepository("central", "https://repo1.maven.org/maven2")
    )
    val module = ModuleID("com.github.sbt", "sbt-native-packager", "1.11.7")
      .withExtraAttributes(Map("scalaVersion" -> "2.12", "sbtVersion" -> "1.0"))

    val fetcher = new CoursierArtifactFetcher(
      mockLogger,
      resolvers,
      Set.empty,
      "2.12.21",
      "2.12",
      extraIvyProps = Map("scalaBinaryVersion" -> "2.12", "sbtBinaryVersion" -> "1.0"),
      artifactClassifiers = Seq.empty,
      lockPomDependencyArtifacts = true
    )

    val (_, _, _, seedErrors) = fetcher(Set(Dependency(module)))
    val (_, artifacts) = fetcher.lockModulePoms(Set(module))
    val relativePaths = artifacts.map(_.relativePath)

    seedErrors.flatMap(_.errors) shouldBe empty
    relativePaths should contain("org/scala-lang/modules/scala-xml_2.12/2.2.0/scala-xml_2.12-2.2.0.jar")
    relativePaths should contain("org/apache/commons/commons-parent/85/commons-parent-85.pom")
    relativePaths should contain("org/junit/junit-bom/5.13.1/junit-bom-5.13.1.pom")
  }

  it should "lock evicted update report POM metadata without locking evicted jars" in {
    val resolvers = Set[Resolver](
      MavenRepository("central", "https://repo1.maven.org/maven2")
    )
    val module = ModuleID("commons-io", "commons-io", "2.11.0")

    val fetcher = new CoursierArtifactFetcher(
      mockLogger,
      resolvers,
      Set.empty,
      "2.12.21",
      "2.12",
      artifactClassifiers = Seq.empty
    )

    val evictedReport = ModuleReport(module, Vector.empty, Vector.empty).withEvicted(true)
    val report = UpdateReport(
      new File("ivy.xml"),
      Vector(ConfigurationReport(ConfigRef("compile"), Vector(evictedReport), Vector.empty)),
      UpdateStats(0L, 0L, 0L, cached = true),
      Map.empty
    )
    val (_, artifacts) = fetcher.fromUpdateReport(report)
    val relativePaths = artifacts.map(_.relativePath)

    relativePaths should contain("commons-io/commons-io/2.11.0/commons-io-2.11.0.pom")
    relativePaths should not contain "commons-io/commons-io/2.11.0/commons-io-2.11.0.jar"
  }

  it should "preserve Ivy resolver patterns for sbt plugin repositories" in {
    val ivyPattern =
      "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)([branch]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"

    val sbtPluginReleases =
      Resolver.url(
        "sbt-plugin-releases",
        sbt.url("https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/")
      )(Patterns(ivyPattern))

    val fetcher = new CoursierArtifactFetcher(
      mockLogger,
      Set(sbtPluginReleases),
      Set.empty,
      "2.12.21",
      "2.12"
    )

    // We don't need to resolve any real dependencies to validate that we emit the correct
    // repo pattern in `repo.nix`. The bug we saw was that patterns were dropped (empty string).
    val (repos, _, _, _) = fetcher(Set.empty)

    repos should contain(NixRepo("nix-sbt-plugin-releases", ivyPattern))
  }
}
