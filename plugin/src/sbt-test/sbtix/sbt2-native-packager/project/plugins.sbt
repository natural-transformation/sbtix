sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("se.nullable.sbtix" % "sbtix" % version)
  case _             => sys.error("The system property 'plugin.version' is not defined.")
}

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

resolvers += Resolver.sbtPluginRepo("releases")
