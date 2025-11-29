sbtPlugin := true

name         := "sbtix"
organization := "se.nullable.sbtix"
version      := "0.4-SNAPSHOT"

publishTo := {
    if (isSnapshot.value) {
      Opts.resolver.sonatypeOssSnapshots.headOption
    } else {
      Some(Opts.resolver.sonatypeStaging)
    }
  }


licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
homepage := Some(url("https://gitlab.com/teozkr/Sbtix"))

scmInfo := Some(
  ScmInfo(
    url("https://gitlab.com/teozkr/sbtix"),
    "scm:git@gitlab.com:teozkr/sbtix.git"
  )
)

pgpPublicRing := Path.userHome / ".gnupg" / "pubring.kbx"
// Secret rings are no more, as of GPG 2.2
// See https://github.com/sbt/sbt-pgp/issues/126
pgpSecretRing := pgpPublicRing.value

enablePlugins(SbtPlugin)

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}"
)
scriptedBufferLog := false

// Explicitly set the sbt version for scripted tests
scriptedSbt := sbtVersion.value

// Ensure the sbtix plugin JAR is copied into the test project source before scripted tests run
scriptedDependencies := {
  val log = streams.value.log
  log.info("[SBTIX_PRE_SCRIPTED] scriptedDependencies starting...")
  val sbtixPluginJar = (Compile / packageBin).value // Get the packaged sbtix plugin JAR
  log.info(s"[SBTIX_PRE_SCRIPTED] Source plugin JAR: ${sbtixPluginJar.getAbsolutePath}, Exists: ${sbtixPluginJar.exists()}")

  // Path to the specific scripted test project's source directory
  // This copies it into src/sbt-test/sbtix/simple/, and scripted will then copy this to the temp test dir.
  val testProjectSourceDir = baseDirectory.value / "src" / "sbt-test" / "sbtix" / "simple"
  val targetJarInTestProjectSource = testProjectSourceDir / "sbtix-plugin-under-test.jar"
  log.info(s"[SBTIX_PRE_SCRIPTED] Target in test source: ${targetJarInTestProjectSource.getAbsolutePath}")

  IO.createDirectory(testProjectSourceDir) // Ensure the directory exists
  IO.copyFile(sbtixPluginJar, targetJarInTestProjectSource, preserveLastModified = true)
  log.info(s"[SBTIX_PRE_SCRIPTED] Copied JAR. Target exists: ${targetJarInTestProjectSource.exists()}")
}

// Explicitly make scripted depend on packageBin of the current project (sbtix plugin)
scripted := scripted.dependsOn(Compile / packageBin).evaluated

publishMavenStyle := false

Compile / packageBin / publishArtifact := true

Test / packageBin / publishArtifact := false

Compile / packageDoc / publishArtifact := false

Compile / packageSrc / publishArtifact := false

Compile / unmanagedResourceDirectories += baseDirectory.value / "nix-exprs"

scalafmtOnCompile := false

libraryDependencies ++= Seq(
  "io.get-coursier" %% "coursier" % "2.1.24",
  "io.get-coursier" %% "coursier-sbt-maven-repository" % "2.1.24",
  "com.lihaoyi" %% "upickle" % "3.1.3",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test
)
