ThisBuild / scalaVersion := "3.7.4"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "evicted-direct-pom",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % "0.7.3",
      "dev.zio" %% "zio-http" % "3.8.0"
    )
  )
