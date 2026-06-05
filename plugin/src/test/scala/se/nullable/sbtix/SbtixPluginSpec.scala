package se.nullable.sbtix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import sbt.ModuleID
import sbt.librarymanagement.UpdateReport

class SbtixPluginSpec extends AnyFlatSpec with Matchers with OptionValues {

  "pluginFetchPlan" should "use the already-resolved plugin update report without re-resolving plugin roots" in {
    val regularPlugin = ModuleID("io.spray", "sbt-revolver", "0.10.0")
    val scalafmtPlugin = ModuleID("org.scalameta", "sbt-scalafmt", "2.5.6")
    val report = null.asInstanceOf[UpdateReport]

    val plan = SbtixPlugin.pluginFetchPlan(
      Set(regularPlugin, scalafmtPlugin),
      Some(report),
      Seq("sources", "tests")
    )

    plan shouldBe a[SbtixPlugin.PluginReportAndDependencyFetches]
    val SbtixPlugin.PluginReportAndDependencyFetches(`report`, fetches) = plan
    fetches shouldBe empty
  }

  it should "resolve declared plugin modules when no update report is available" in {
    val regularPlugin = ModuleID("io.spray", "sbt-revolver", "0.10.0")
    val scalafmtPlugin = ModuleID("org.scalameta", "sbt-scalafmt", "2.5.6")

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
