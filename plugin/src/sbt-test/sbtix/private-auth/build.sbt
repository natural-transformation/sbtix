ThisBuild / scalaVersion := "2.13.9"

lazy val one = project.settings(
  credentials += Credentials(realm = "Very secret stuff!", host = "localhost", userName = "test", passwd = "test"),
  libraryDependencies ++= Seq(
    "com.typesafe.slick" %% "slick" % "3.3.3",
    "private-org" % "private-name" % "1.2.3"
  )
)
lazy val two = project
lazy val three = project.settings(
  credentials += Credentials(realm = "Very secret stuff!", host = "localhost", userName = "test", passwd = "test")
).dependsOn(one).enablePlugins(JavaAppPackaging)

lazy val root = project.in(file(".")).aggregate(one, two, three)

// Served via serve-authenticated.py
ThisBuild / resolvers += "private-demo" at "http://localhost:9877/plugin/src/sbt-test/artifacts"
