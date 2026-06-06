ThisBuild / scalaVersion := "3.3.7"
ThisBuild / version := "0.1.0"

lazy val stage = taskKey[File]("Stage a minimal executable for sbtix buildSbtProgram")

lazy val root = (project in file("."))
  .settings(
    name := "scala3-test",
    stage := {
      (Compile / compile).value
      val stageDir = target.value / "universal" / "stage"
      val binDir = stageDir / "bin"
      IO.delete(stageDir)
      IO.createDirectory(binDir)
      val executable = binDir / "scala3-fixture"
      IO.write(executable, "#!/usr/bin/env sh\necho hello scala3\n")
      executable.setExecutable(true)
      stageDir
    }
  )
