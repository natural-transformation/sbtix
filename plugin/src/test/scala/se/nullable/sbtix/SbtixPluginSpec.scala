package se.nullable.sbtix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import sbt.ModuleID
import sbt.librarymanagement.UpdateReport

class SbtixPluginSpec extends AnyFlatSpec with Matchers with OptionValues {

  "pluginFetchPlan" should "resolve declared plugin modules when sbt-scalafmt also has an update report" in {
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
    fetches.map(_.context.getOrElse("")) should contain only "sbtixGenerate (plugin)"

    val regularFetch = fetches.find(_.context.contains("sbtixGenerate (plugin)")).value
    regularFetch.artifactClassifiers shouldBe Seq("sources", "tests")
    regularFetch.dependencies.map(_.moduleId) shouldBe Set(regularPlugin)
  }
}
