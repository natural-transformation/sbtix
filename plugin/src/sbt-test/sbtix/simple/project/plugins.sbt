if (sys.props.contains("plugin.version")) {
  Seq(addSbtPlugin("se.nullable.sbtix" % "sbtix" % sys.props("plugin.version")))
} else {
  Seq()
}

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.4")

// Not actually needed/used, but here to test sbtix correctly fetches
// plugins that are not on Maven Central and only on sbt-plugin-releases:
addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.25")
