# Example-only manual overrides.
# NOTE: sbtix itself does not rely on this; it is provided as a user fallback.
{
  "repos" = {
    "nix-public" = "";
    "nix-typesafe-ivy-releases" = "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]";
  };
  "artifacts" = {
    # Add per-project overrides here if you must pin or inject artifacts manually.
  };
}

