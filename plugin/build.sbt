sbtPlugin := true

name         := "sbtix"
organization := "se.nullable.sbtix"
version      := "0.4.1-SNAPSHOT"

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
  s"-Dplugin.version=${version.value}",
  // Scripted tests run sbt directly (not via the sbtix wrapper). Provide the minimum
  // metadata needed for `genComposition` to generate store-backed plugin bootstrap:
  // - `sbtix.sourcePath` lets Nix build the sbtix plugin from this checkout (nix-build flow)
  // - `sbtix.pluginJarPath` pins the loaded plugin jar so we can detect stale classpaths
  s"-Dsbtix.sourcePath=${baseDirectory.value.getParentFile.getAbsolutePath}",
  s"-Dsbtix.pluginJarPath=${(Path.userHome / ".ivy2" / "local" / "se.nullable.sbtix" / "sbtix" / "scala_2.12" / "sbt_1.0" / version.value / "jars" / "sbtix.jar").getAbsolutePath}"
)
scriptedBufferLog := false

// Explicitly set the sbt version for scripted tests
scriptedSbt := sbtVersion.value

// Scripted tests must not create or require JARs inside the test projects.
// We bootstrap the plugin from Nix (see `sbtix.sourcePath` above) and keep the
// sbtix plugin itself available via `publishLocal`.
scriptedDependencies := {
  val log = streams.value.log
  log.info("[SBTIX_PRE_SCRIPTED] scriptedDependencies: no-op (no workspace JAR bootstrap)")
}

// Ensure scripted runs with the freshly published plugin in the local Ivy cache
scripted := scripted.dependsOn(publishLocal).evaluated

publishMavenStyle := false

Compile / packageBin / publishArtifact := true

Test / packageBin / publishArtifact := false

Compile / packageDoc / publishArtifact := false

Compile / packageSrc / publishArtifact := false

Compile / resourceGenerators += Def.task {
  val base = baseDirectory.value
  val managed = (Compile / resourceManaged).value / "templates"
  IO.createDirectory(managed)
  val files = Seq(
    "sbtix.nix" -> (base / "nix-exprs" / "sbtix.nix"),
    "manual-repo.nix" -> (base / "nix-exprs" / "manual-repo.nix"),
    "sbtix-plugin-repo.nix" -> (base / "sbtix-plugin-repo.nix")
  )
  files.map { case (name, src) =>
    val dest = managed / name
    IO.copyFile(src, dest)
    dest
  }
}

scalafmtOnCompile := false

libraryDependencies ++= Seq(
  "io.get-coursier" %% "coursier" % "2.1.24",
  "io.get-coursier" %% "coursier-sbt-maven-repository" % "2.1.24",
  "com.lihaoyi" %% "upickle" % "3.1.3",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test
)
