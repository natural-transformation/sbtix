sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("se.nullable.sbtix" % "sbtix" % version)
  case _ => sys.error("The system property 'plugin.version' is not defined.")
}

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")
addSbtPlugin("net.vonbuchholtz" % "sbt-dependency-check" % "5.1.0")

resolvers += Resolver.sbtPluginRepo("releases")
resolvers += "Jaspersoft third-party" at "https://jaspersoft.jfrog.io/jaspersoft/third-party-ce-artifacts/"
