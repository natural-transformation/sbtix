val org = "sbtix-test-multibuild"

val ver = "0.1.0-SNAPSHOT"

organization := org

name := "mb-three"

version := ver

libraryDependencies += org %% "mb-two" % ver extra ("nix" -> "")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.5")
enablePlugins(JavaAppPackaging)
