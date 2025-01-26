if (sys.props.contains("plugin.version")) {
  Seq(addSbtPlugin("se.nullable.sbtix" % "sbtix" % sys.props("plugin.version")))
} else {
  Seq()
}

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.0")
