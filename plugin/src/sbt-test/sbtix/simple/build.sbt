version := "0.1.0"
scalaVersion := "2.13.18"

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.20" % Test
)

lazy val one = project.settings(
  libraryDependencies += "com.typesafe.slick" %% "slick" % "3.6.1"
)

lazy val two = project

lazy val three = project
  .dependsOn(one)
  .enablePlugins(JavaAppPackaging)
  .settings(
    Compile / mainClass := Some("MainThree"),

    // Force the JavaAppPackaging to create a script
    executableScriptName := "three"
  )

lazy val root = project.in(file(".")).settings(name := "simple-test").aggregate(one, two, three)
