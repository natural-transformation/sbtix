sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("se.nullable.sbtix" % "sbtix" % version)
  case _ => sys.error("The system property 'plugin.version' is not defined.")
}
