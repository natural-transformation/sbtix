package se.nullable.sbtix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import sbt.ModuleID
import sbt.librarymanagement.{ConfigRef, ConfigurationReport, ModuleReport, UpdateReport, UpdateStats}
import java.io.File

class SbtixPluginSpec extends AnyFlatSpec with Matchers with OptionValues {

  private def updateReport(modules: ModuleID*): UpdateReport = {
    val reports =
      modules.map(module => ModuleReport(module, Vector.empty, Vector.empty)).toVector
    UpdateReport(
      new File("ivy.xml"),
      Vector(ConfigurationReport(ConfigRef("compile"), reports, Vector.empty)),
      UpdateStats(0L, 0L, 0L, cached = true),
      Map.empty
    )
  }

  "pluginFetchPlan" should "use the already-resolved plugin update report without re-resolving plugin roots" in {
    val regularPlugin = ModuleID("io.spray", "sbt-revolver", "0.10.0")
    val scalafmtPlugin = ModuleID("org.scalameta", "sbt-scalafmt", "2.6.1")
    val report = updateReport(regularPlugin, scalafmtPlugin)

    val plan = SbtixPlugin.pluginFetchPlan(
      Set(regularPlugin, scalafmtPlugin),
      Some(report),
      Seq("sources", "tests")
    )

    plan shouldBe a[SbtixPlugin.PluginReportAndDependencyFetches]
    val SbtixPlugin.PluginReportAndDependencyFetches(`report`, fetches) = plan
    fetches shouldBe empty
  }

  it should "prefer the resolved plugin report even when declared plugin parsing found no modules" in {
    val report = updateReport(ModuleID("com.example", "sbt-declared-elsewhere", "1.0.0"))

    val plan = SbtixPlugin.pluginFetchPlan(
      Set.empty,
      Some(report),
      Seq("sources")
    )

    plan shouldBe a[SbtixPlugin.PluginReportAndDependencyFetches]
    val SbtixPlugin.PluginReportAndDependencyFetches(`report`, fetches) = plan
    fetches shouldBe empty
  }

  it should "ignore empty plugin update reports when no declared plugins were found" in {
    val plan = SbtixPlugin.pluginFetchPlan(
      Set.empty,
      Some(updateReport()),
      Seq("sources")
    )

    plan shouldBe SbtixPlugin.NoPluginFetch
  }

  it should "resolve declared plugin modules when no update report is available" in {
    val regularPlugin = ModuleID("io.spray", "sbt-revolver", "0.10.0")
    val scalafmtPlugin = ModuleID("org.scalameta", "sbt-scalafmt", "2.6.1")

    val plan = SbtixPlugin.pluginFetchPlan(
      Set(regularPlugin, scalafmtPlugin),
      None,
      Seq("sources", "tests")
    )

    plan shouldBe a[SbtixPlugin.PluginDependencyFetches]
    val SbtixPlugin.PluginDependencyFetches(fetches) = plan
    fetches.map(_.context.getOrElse("")) should contain allOf (
      "sbtixGenerate (plugin)",
      "sbtixGenerate (sbt-scalafmt plugin)"
    )

    val regularFetch = fetches.find(_.context.contains("sbtixGenerate (plugin)")).value
    regularFetch.artifactClassifiers shouldBe Seq("sources", "tests")
    regularFetch.dependencies.map(_.moduleId) shouldBe Set(regularPlugin)

    val scalafmtFetch = fetches.find(_.context.exists(_.contains("sbt-scalafmt plugin"))).value
    scalafmtFetch.artifactClassifiers shouldBe empty
    scalafmtFetch.dependencies.map(_.moduleId) shouldBe Set(scalafmtPlugin)
  }
}
