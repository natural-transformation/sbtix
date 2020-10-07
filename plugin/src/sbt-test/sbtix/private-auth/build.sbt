scalaVersion in ThisBuild := "2.12.12"

lazy val one = project.settings(
  coursierCredentials += "private demo" -> coursier.Credentials("test", "test"),
  libraryDependencies ++= Seq(
    "com.typesafe.slick" %% "slick" % "3.3.3",
    "private-org" % "private-name" % "1.2.3"
  )
)
lazy val two = project
lazy val three = project.settings(
  coursierCredentials += "private demo" -> coursier.Credentials("test", "test")
).dependsOn(one).enablePlugins(JavaAppPackaging)

lazy val root = project.in(file(".")).aggregate(one, two, three)

resolvers in ThisBuild += "private demo" at "https://files.nullable.se/sbtix-demo-private/"
