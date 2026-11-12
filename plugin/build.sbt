val coursier = "2.1.26"

// The path `publishLocal` writes the plugin jar to. sbt 2 plugins are published
// with an `_sbt2_3` style suffix (sbt major version and Scala binary version).
def localPluginJarPath(org: String, module: String, version: String, scalaBin: String, sbtBin: String) = {
  val sbtMajor = sbtBin.takeWhile(_ != '.')
  val artifact = s"${module}_sbt${sbtMajor}_$scalaBin"
  Path.userHome / ".ivy2" / "local" / org / artifact / version / "jars" / s"$artifact.jar"
}

// coursier is used through its Scala 2.13 artifacts. Generated Metals/Bloop
// plugin files bring Scala 2.13 builds of the same libraries (jsoniter-scala),
// and mixing those with `_3` builds makes sbt reject the plugin classpath.
def coursierDependency(module: String): ModuleID =
  ("io.get-coursier" %% module % coursier)
    .cross(CrossVersion.for3Use2_13)
    .exclude("org.scala-lang.modules", "scala-collection-compat_2.13")
    .exclude("org.scala-lang.modules", "scala-xml_2.13")

// No `scalaVersion` here on purpose. An sbt plugin is compiled with the Scala
// version that sbt itself runs on (3.8.4 for sbt 2.0.x). sbt compiles build
// files with that compiler, and it cannot read the TASTy of a newer Scala 3
// minor, so a plugin built with a newer Scala would not load.
sbtPlugin := true

name         := "sbtix"
organization := "se.nullable.sbtix"
version      := "2.0.0"

publishTo := {
  if (isSnapshot.value) Some(Resolver.sonatypeCentralSnapshots)
  else localStaging.value
}


licenses := Seq(License.MIT)
homepage := Some(uri("https://github.com/natural-transformation/sbtix"))

scmInfo := Some(
  ScmInfo(
    uri("https://github.com/natural-transformation/sbtix"),
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
  s"-Dsbtix.pluginJarPath=${localPluginJarPath(organization.value, name.value, version.value, scalaBinaryVersion.value, sbtBinaryVersion.value).getAbsolutePath}"
)
scriptedBufferLog := false

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
    "manual-repo.nix" -> (base / "nix-exprs" / "manual-repo.nix")
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
  ("com.lihaoyi" %% "upickle" % "4.4.3").cross(CrossVersion.for3Use2_13),
  "org.scalatest" %% "scalatest" % "3.2.20" % Test
)
