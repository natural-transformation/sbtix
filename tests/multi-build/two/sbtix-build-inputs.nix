# This file contains a manually maintained definition of
# locally-built dependencies.

# Do not rename this file: it is used both by the build
# itself and by the `sbtix-gen` tool which is used to
# update the nix definitions for building the project.
{ pkgs ? import <nixpkgs> {} }:

pkgs.callPackage ../one {}
