package se.nullable.sbtix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.{CrossVersion, Logger, ModuleID}

class CoursierArtifactFetcherUnitSpec extends AnyFlatSpec with Matchers {
  private val noopLogger = new Logger {
    def trace(t: => Throwable): Unit = ()
    def success(message: => String): Unit = ()
    def log(level: sbt.Level.Value, message: => String): Unit = ()
  }

  "modulePomPath" should "use resolved Scala cross-versioned module names" in {
    val fetcher = new CoursierArtifactFetcher(
      noopLogger,
      resolvers = Set.empty,
      credentials = Set.empty,
      scalaVersion = "3.7.4",
      scalaBinaryVersion = "3"
    )
    val module = ModuleID("dev.zio", "zio-json", "0.7.3").cross(CrossVersion.binary)

    fetcher.modulePomPath(module) shouldBe "dev/zio/zio-json_3/0.7.3/zio-json_3-0.7.3.pom"
  }
}
