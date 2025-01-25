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

publishMavenStyle := false

Compile / packageBin / publishArtifact := true

Test / packageBin / publishArtifact := false

Compile / packageDoc / publishArtifact := false

Compile / packageSrc / publishArtifact := false

Compile / unmanagedResourceDirectories += baseDirectory.value / "nix-exprs"

scalafmtOnCompile := false

libraryDependencies ++= Seq(
  "io.get-coursier" %% "coursier" % "2.0.16",
)
