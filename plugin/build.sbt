sbtPlugin := true

name := "sbtix"
organization := "se.nullable.sbtix"
version := "0.2-SNAPSHOT"

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

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.3")

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}"
)
scriptedBufferLog := false

publishMavenStyle := false

publishArtifact in (Compile, packageBin) := true

publishArtifact in (Test, packageBin) := false

publishArtifact in (Compile, packageDoc) := false

publishArtifact in (Compile, packageSrc) := false

unmanagedResourceDirectories in Compile += baseDirectory.value / "nix-exprs"

scalafmtOnCompile := true

libraryDependencies += "com.slamdata" %% "matryoshka-core" % "0.18.3"
