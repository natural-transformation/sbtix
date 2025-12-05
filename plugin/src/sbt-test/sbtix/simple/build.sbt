name := "simple-test"
version := "0.1.0"
scalaVersion := "2.13.16"

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.15" % Test
)

lazy val one = project.settings(
  libraryDependencies += "com.typesafe.slick" %% "slick" % "3.3.3"
)

lazy val two = project

lazy val three = project
  .dependsOn(one)
  .enablePlugins(JavaAppPackaging)
  .settings(
    Compile / mainClass := Some("MainThree"),
    // Make sure we create an executable application with the stage task
    Universal / packageBin := {
      val original = (Universal / packageBin).value
      // Ensure this runs during Nix build - just to help with debugging
      println(s"[PACKAGE_DEBUG] Created package at: ${original.getAbsolutePath}")
      println(s"[PACKAGE_DEBUG] Universal artifacts created: ${IO.listFiles(original).mkString(", ")}")
      original
    },
    
    // Force the JavaAppPackaging to create a script
    executableScriptName := "three"
  )

lazy val root = project.in(file(".")).aggregate(one, two, three)