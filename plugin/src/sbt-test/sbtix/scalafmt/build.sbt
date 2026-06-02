ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0"

lazy val stage = taskKey[File]("Stage a minimal executable for sbtix buildSbtProgram")

lazy val root = (project in file("."))
  .settings(
    name := "scalafmt-test",
    sbtixArtifactClassifiers := Seq.empty,
    scalafmtOnCompile := true,
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
