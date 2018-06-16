if (sys.props.contains("plugin.version")) {
  Seq(addSbtPlugin("se.nullable.sbtix" % "sbtix" % sys.props("plugin.version")))
} else {
  Seq()
}

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.3")

import coursier.Keys._
classpathTypes += "maven-plugin"
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.2")
