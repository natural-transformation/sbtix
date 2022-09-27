sbtPlugin := true

name         := "sbtix"
organization := "se.nullable.sbtix"
version      := "0.3-SNAPSHOT"

publishTo := Some(
  if (isSnapshot.value) {
    Opts.resolver.sonatypeSnapshots
  } else {
    Opts.resolver.sonatypeStaging
  }
)

licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
homepage := Some(url("https://gitlab.com/teozkr/Sbtix"))

scmInfo := Some(
  ScmInfo(
    url("https://gitlab.com/teozkr/sbtix"),
    "scm:git@gitlab.com:teozkr/sbtix.git"
  )
)

developers := List(
  Developer(
    id = "teozkr",
    name = "Teo Klestrup Röijezon",
    email = "teo@nullable.se",
    url = url("https://nullable.se")
  )
)

useGpg := true

pgpPublicRing := Path.userHome / ".gnupg" / "pubring.kbx"
// Secret rings are no more, as of GPG 2.2
// See https://github.com/sbt/sbt-pgp/issues/126
pgpSecretRing := pgpPublicRing.value

addSbtPlugin("io.get-coursier" % "sbt-coursier"               % "2.0.11-1")
addSbtPlugin("io.get-coursier" % "sbt-coursier-shared-shaded" % "2.0.4")

enablePlugins(SbtPlugin)

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}"
)
scriptedBufferLog := false

publishMavenStyle := false

Compile / packageBin / publishArtifact := true

Test / packageBin / publishArtifact := false

Compile / packageDoc / publishArtifact := false

Compile / packageSrc / publishArtifact := false

Compile / unmanagedResourceDirectories += baseDirectory.value / "nix-exprs"

scalafmtOnCompile := false

libraryDependencies += "org.scalaz"   %% "scalaz-core"     % "7.3.6"
libraryDependencies += "com.slamdata" %% "matryoshka-core" % "0.18.3"
// TODO Replace matryoshka with droste
// libraryDependencies += "io.higherkindness" %% "droste-core" % "0.9.0"
