sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("se.nullable.sbtix" % "sbtix" % version)
  case _ => sys.error("The system property 'plugin.version' is not defined.")
}

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

resolvers += Resolver.sbtPluginRepo("releases")
resolvers += "Jaspersoft third-party" at "https://jaspersoft.jfrog.io/jaspersoft/third-party-ce-artifacts/"
