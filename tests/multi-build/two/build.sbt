val org = "sbtix-test-multibuild"

val ver = "0.1.0-SNAPSHOT"

organization := org

name := "mb-two"

version := ver

scalaVersion := "3.9.0"

libraryDependencies += (org %% "mb-one" % ver).extra("nix" -> "")

// For this one, we don't set the "nix" extra attribute, to test that we can detect nix-built dependencies without it.
// projectID := projectID.value.extra("nix" -> "")
