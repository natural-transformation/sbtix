ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0"

lazy val stage = taskKey[File]("Stage a minimal executable for sbtix buildSbtProgram")

lazy val root = (project in file("."))
  .settings(
    name := "scalafmt-test",
    sbtixArtifactClassifiers := Seq("sources"),
    scalafmtOnCompile := true,
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.4.2",
      ("uk.co.real-logic" % "sbe-tool" % "1.32.1").pomOnly(),
      "org.apache.pulsar" % "bouncy-castle-bc" % "3.3.3"
    ),
    stage := {
      (Compile / compile).value
      val stageDir = target.value / "universal" / "stage"
      val binDir = stageDir / "bin"
      IO.delete(stageDir)
      IO.createDirectory(binDir)
      val executable = binDir / "scalafmt-fixture"
      IO.write(executable, "#!/usr/bin/env sh\necho hello scalafmt\n")
      executable.setExecutable(true)
      stageDir
    }
  )
