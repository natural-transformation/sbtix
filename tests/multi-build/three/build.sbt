val org = "sbtix-test-multibuild"

val ver = "0.1.0-SNAPSHOT"

organization := org

name := "mb-three"

version := ver

libraryDependencies += org %% "mb-two" % ver

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.4")
enablePlugins(JavaAppPackaging)
