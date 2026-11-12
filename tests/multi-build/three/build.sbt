val org = "sbtix-test-multibuild"

val ver = "0.1.0-SNAPSHOT"

organization := org

name := "mb-three"

version := ver

scalaVersion := "3.9.0"

libraryDependencies += org %% "mb-two" % ver

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
enablePlugins(JavaAppPackaging)
