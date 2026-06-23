val scala212 = "2.12.20"
val scala3   = "3.8.4"
val sbt1     = "1.12.11"
val sbt2     = "2.0.0"
val coursier = "2.1.24"

def localPluginJarPath(org: String, module: String, version: String, scalaBin: String, sbtBin: String) = {
  val ivyLocalOrg = Path.userHome / ".ivy2" / "local" / org
  if (sbtBin == "1.0") {
    ivyLocalOrg / module / s"scala_$scalaBin" / s"sbt_$sbtBin" / version / "jars" / s"$module.jar"
  } else {
    val sbtMajor = sbtBin.takeWhile(_ != '.')
    val artifact = s"${module}_sbt${sbtMajor}_$scalaBin"
    ivyLocalOrg / artifact / version / "jars" / s"$artifact.jar"
  }
}

def coursierDependency(module: String): ModuleID =
  ("io.get-coursier" %% module % coursier)
    .cross(CrossVersion.for3Use2_13)
    .exclude("org.scala-lang.modules", "scala-collection-compat_2.13")
    .exclude("org.scala-lang.modules", "scala-xml_2.13")

ThisBuild / scalaVersion := scala212
ThisBuild / crossScalaVersions := Seq(scala212, scala3)

sbtPlugin := true

pluginCrossBuild / sbtVersion := {
  scalaBinaryVersion.value match {
    case "2.12" => sbt1
    case _      => sbt2
  }
}

name         := "sbtix"
organization := "se.nullable.sbtix"
version      := "1.2.7"

publishTo := {
  if (isSnapshot.value) Some(Resolver.sonatypeCentralSnapshots)
  else localStaging.value
}


licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
homepage := Some(url("https://github.com/natural-transformation/sbtix"))

scmInfo := Some(
  ScmInfo(
    url("https://github.com/natural-transformation/sbtix"),
    "scm:git:https://github.com/natural-transformation/sbtix.git"
  )
)

pgpPublicRing := Path.userHome / ".gnupg" / "pubring.kbx"
// Secret rings are no more, as of GPG 2.2
// See https://github.com/sbt/sbt-pgp/issues/126
pgpSecretRing := pgpPublicRing.value

enablePlugins(SbtPlugin)

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}",
  // Make sure the sbt processes spawned by `scripted` use the same user home
  // directory as this sbt instance. Otherwise they may pick up a different
  // ~/.ivy2 cache (e.g. the real home directory), which then trips sbtix's
  // "plugin jar mismatch" guard.
  s"-Duser.home=${Path.userHome.getAbsolutePath}",
  // Scripted tests run sbt directly (not via the sbtix wrapper). Provide the minimum
  // metadata needed for `genComposition` to generate store-backed plugin bootstrap:
  // - `sbtix.sourcePath` lets Nix build the sbtix plugin from this checkout (nix-build flow)
  // - `sbtix.pluginJarPath` pins the loaded plugin jar so we can detect stale classpaths
  s"-Dsbtix.sourcePath=${baseDirectory.value.getParentFile.getAbsolutePath}",
  s"-Dsbtix.pluginJarPath=${localPluginJarPath(organization.value, name.value, version.value, scalaBinaryVersion.value, (pluginCrossBuild / sbtBinaryVersion).value).getAbsolutePath}"
)
scriptedBufferLog := false

Compile / unmanagedSources / excludeFilter := {
  val current = (Compile / unmanagedSources / excludeFilter).value
  if (scalaBinaryVersion.value == "3") current || "NixPlugin.scala" else current
}

// Explicitly set the sbt version for scripted tests
scriptedSbt := (pluginCrossBuild / sbtVersion).value

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
  coursierDependency("coursier"),
  coursierDependency("coursier-sbt-maven-repository"),
  ("com.lihaoyi" %% "upickle" % "4.4.3").cross(CrossVersion.for3Use2_13),
  "org.scalatest" %% "scalatest" % "3.2.20" % Test
)
