{ pkgs ? import <nixpkgs> {}
, cleanSource ? pkgs.lib.cleanSource
}:

let
  sbtix = pkgs.callPackage ./sbtix.nix {};
  inherit (pkgs.lib) optional;

  sbtixSource = /nix/store/9gp52shvqnskkzh05da7klbxjqz88b9c-source;

  sbtixPluginRepos = [
    (import (sbtixSource + "/plugin/repo.nix"))
    (import (sbtixSource + "/plugin/project/repo.nix"))
    (import (sbtixSource + "/plugin/nix-exprs/manual-repo.nix"))
  ];

  sbtixPluginIvy = sbtix.buildSbtLibrary {
    name = "sbtix-plugin";
    src = sbtix.withoutSbtOutput (cleanSource (sbtixSource + "/plugin"));
    repo = sbtixPluginRepos;
  };

  pluginVersion =
    let versions = builtins.attrNames (builtins.readDir "${sbtixPluginIvy}/se.nullable.sbtix/sbtix_sbt2_3");
    in builtins.head versions;

  sbtixPluginJarPath = "${sbtixPluginIvy}/se.nullable.sbtix/sbtix_sbt2_3/${pluginVersion}/jars/sbtix_sbt2_3.jar";


  manualRepo = import ./manual-repo.nix;
  repoLock = import ./repo.nix;
  projectRepo = import ./project/repo.nix;
  projectMetaRepoPath = ./project/project/repo.nix;
  projectMetaRepo =
    if builtins.pathExists projectMetaRepoPath
    then import projectMetaRepoPath
    else {};
  rootEntries = builtins.readDir ./.;
  subprojectRepos =
    builtins.concatLists (builtins.map (name:
      let
        entryType = builtins.getAttr name rootEntries;
        repoPath = ./. + "/${name}/repo.nix";
        projectRepoPath = ./. + "/${name}/project/repo.nix";
      in
        if entryType == "directory" && name != "project"
        then
          (optional (builtins.pathExists repoPath) (import repoPath))
          ++ (optional (builtins.pathExists projectRepoPath) (import projectRepoPath))
        else []
    ) (builtins.attrNames rootEntries));

  repositories =
    [ repoLock projectRepo projectMetaRepo ]
    ++ subprojectRepos
    ++ optional (builtins.length (builtins.attrNames manualRepo.artifacts) > 0) manualRepo;
  
  buildInputsPath = ./sbtix-build-inputs.nix;
  sbtixInputs =
    if builtins.pathExists buildInputsPath
    then pkgs.callPackage buildInputsPath {}
    else "";
in
  sbtix.buildSbtProgram {
    name = "template-generation";
    src = sbtix.withoutSbtOutput (cleanSource ./.);
    repo = repositories;
    sbtOptions = "-Dplugin.version=${pluginVersion}";
    sbtixBuildInputs = sbtixInputs;
    pluginBootstrap = ''
      pluginJar="${sbtixPluginJarPath}"

ivyDir="./.ivy2-home/local/se.nullable.sbtix/sbtix_sbt2_3/${pluginVersion}"
mkdir -p "$ivyDir/jars" "$ivyDir/ivys" "$ivyDir/poms"
if [ -n "${pluginJar:-}" ] && [ -f "$pluginJar" ]; then
  cp "$pluginJar" "$ivyDir/jars/sbtix_sbt2_3.jar"
else
  echo "sbtix: unable to locate plugin jar; ensure SBTIX_SOURCE_URL/REV/NAR_HASH or SBTIX_PLUGIN_JAR_PATH are set." 1>&2
  exit 1
fi
  cat <<POM_EOF > "$ivyDir/poms/sbtix_sbt2_3-${pluginVersion}.pom"
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
      <modelVersion>4.0.0</modelVersion>
      <groupId>se.nullable.sbtix</groupId>
      <artifactId>sbtix_sbt2_3</artifactId>
      <version>${pluginVersion}</version>
      <name>sbtix Plugin</name>
      <description>Locally provided sbtix plugin for Nix build</description>
      <packaging>jar</packaging>
    </project>
POM_EOF
  cat <<IVY_EOF > "$ivyDir/ivys/ivy.xml"
    <ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">
      <info organisation="se.nullable.sbtix"
            module="sbtix_sbt2_3"
            revision="${pluginVersion}"
            status="release"
            publication="0000000000000">
        <description>
          sbtix plugin (locally provided for Nix build)
        </description>
      </info>
      <configurations>
        <conf name="compile" visibility="public" description=""/>
        <conf name="default" visibility="public" description="" extends="compile"/>
        <conf name="master" visibility="public" description=""/>
        <conf name="provided" visibility="public" description=""/>
        <conf name="runtime" visibility="public" description="" extends="compile"/>
        <conf name="sources" visibility="public" description=""/>
        <conf name="test" visibility="public" description="" extends="runtime"/>
      </configurations>
      <publications>
        <artifact name="sbtix_sbt2_3" type="jar" ext="jar" conf="compile"/>
      </publications>
      <dependencies></dependencies>
    </ivy-module>
IVY_EOF
ln -sf ivy.xml "$ivyDir/ivys/ivy-${pluginVersion}.xml"
    '';
  }
