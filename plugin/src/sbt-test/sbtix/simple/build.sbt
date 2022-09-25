scalaVersion in ThisBuild := "2.13.9"

lazy val one = project.settings(
  libraryDependencies += "com.typesafe.slick" %% "slick" % "3.3.3"
)
lazy val two = project
lazy val three = project.dependsOn(one).enablePlugins(JavaAppPackaging)

lazy val root = project.in(file(".")).aggregate(one, two, three)