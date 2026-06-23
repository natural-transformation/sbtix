ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "sbt2-native-packager",
    Compile / mainClass := Some("Main")
  )
