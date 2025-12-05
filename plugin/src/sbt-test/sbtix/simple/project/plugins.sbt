sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("se.nullable.sbtix" % "sbtix" % version)
  case _ => sys.error("The system property 'plugin.version' is not defined.")
}

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.0")

// Not actually needed/used, but here to test sbtix correctly fetches
// plugins that are not on Maven Central and only on sbt-plugin-releases:
addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.25")

resolvers += Resolver.sbtPluginRepo("releases")
