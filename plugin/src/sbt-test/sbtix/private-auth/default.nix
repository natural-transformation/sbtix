{ pkgs ? import <nixpkgs> {} }:

let
  sbtixRepo = import ./repo.nix {
    inherit (pkgs) fetchurl fetchgit fetchzip;
  };

  sbtixRepos = pkgs.writeText "sbtix-repositories.conf" ''
[repositories]
  local
  maven-central: https://repo1.maven.org/maven2/
'';

  sbtWithRepo = pkgs.sbt.override {
    jre = pkgs.jdk11;
  };

in pkgs.stdenv.mkDerivation {
  name = "sbtix-project";
  src = ./.; # This includes sbtix-plugin-under-test.jar

  buildInputs = [ sbtWithRepo pkgs.jdk11 ];

  phases = [ "unpackPhase" "setupPhase" "buildPhase" "installPhase" "fixupPhase" ];

  setupPhase = ''
    mkdir -p project
    echo "sbt.version=1.10.7" > ./project/build.properties
    echo "[NIX_BUILD_DEBUG] Created project/build.properties with sbt.version=1.10.7"
  '';

  buildPhase = ''
    # Configure build environment variables - using absolute paths with $(pwd)
    export COURSIER_CACHE=$(pwd)/.coursier-cache
    export SBT_OPTS="-Dsbt.offline=true -Dsbt.log.noformat=true"
    export SBT_OPTS="$SBT_OPTS -Dsbt.repository.config=${sbtixRepos}"
    export SBT_OPTS="$SBT_OPTS -Dsbt.global.base=$(pwd)/.sbt-global"
    export SBT_OPTS="$SBT_OPTS -Dsbt.ivy.home=$(pwd)/.ivy2-home"
    export SBT_OPTS="$SBT_OPTS -Dsbt.boot.directory=$(pwd)/.sbt-boot"
    export SBT_OPTS="$SBT_OPTS -Dcoursier.cache=$(pwd)/.coursier-cache"
    export SBT_OPTS="$SBT_OPTS -Dplugin.version=0.4-SNAPSHOT"
    export SBT_OPTS="$SBT_OPTS -Dsbtix.debug=true"

    localHome="$(pwd)/.sbt-home"
    localCache="$localHome/.cache"
    mkdir -p "$localHome" "$localCache"
    export SBT_OPTS="$SBT_OPTS -Duser.home=$localHome"
    export HOME="$localHome"
    export XDG_CACHE_HOME="$localCache"

    echo "[NIX_BUILD_DEBUG] Setting up local Ivy repository for sbtix plugin version 0.4-SNAPSHOT"

    # Define ivyDir with explicitly absolute path for consistent behavior
    # IMPORTANT: Never use $ivyDir which breaks Nix variable interpolation
    ivyDir="$(pwd)/.ivy2-home/local/se.nullable.sbtix/sbtix/scala_2.12/sbt_1.0/0.4-SNAPSHOT"

    # Create local directories for Ivy repositories
    mkdir -p $ivyDir/jars
    mkdir -p $ivyDir/ivys
    mkdir -p $ivyDir/poms

    echo "[NIX_BUILD_DEBUG] Copying sbtix-plugin-under-test.jar to the ivy jar directory"
    cp ./sbtix-plugin-under-test.jar $ivyDir/jars/sbtix.jar

    echo "[NIX_BUILD_DEBUG] Writing POM to $ivyDir/poms/sbtix-0.4-SNAPSHOT.pom"
    cat <<POM_EOF > $ivyDir/poms/sbtix-0.4-SNAPSHOT.pom
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>se.nullable.sbtix</groupId>
  <artifactId>sbtix</artifactId>
  <version>0.4-SNAPSHOT</version>
  <name>sbtix Plugin</name>
  <description>Locally provided sbtix plugin for Nix build</description>
  <packaging>jar</packaging>
</project>
POM_EOF

    # Generate the Ivy XML files for compatibility with both Coursier versions
    # Step 1: Create the primary ivy.xml file first (for Coursier >= 2.1.17)
    echo "[NIX_BUILD_DEBUG] Writing primary ivy.xml file for Coursier 2.1.17+"
    cat <<IVY_EOF > $ivyDir/ivys/ivy.xml
<ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">
  <info organisation="se.nullable.sbtix"
        module="sbtix"
        revision="0.4-SNAPSHOT"
        status="release"
        publication="1764441982405"
        e:sbtVersion="1.0"
        e:scalaVersion="2.12">
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
    <artifact name="sbtix" type="jar" ext="jar" conf="compile"/>
    <artifact name="sbtix" type="src" ext="jar" conf="sources" e:classifier="sources"/>
  </publications>
  <dependencies></dependencies>
</ivy-module>
IVY_EOF

    # Step 2: Create the ivy-version.xml symlink (for Coursier <= 2.1.16 backward compatibility)
    echo "[NIX_BUILD_DEBUG] Creating ivy-0.4-SNAPSHOT.xml symlink for Coursier 2.1.16 and earlier compatibility"
    ln -sf ivy.xml "$ivyDir/ivys/ivy-0.4-SNAPSHOT.xml"

    echo "[NIX_BUILD_DEBUG] Files in Ivy repo:"
    ls -R $ivyDir

    echo "[NIX_BUILD_DEBUG] Attempting sbt compile with verbose logging..."
    # Compile the project
    sbt compile
    # Check if any subprojects use sbt-native-packager and have the stage task
    if [ -n "$(find . -maxdepth 2 -type f -name 'plugins.sbt' -exec grep -l 'sbt-native-packager' {} \;)" ]; then
      echo "[NIX_BUILD_DEBUG] Found sbt-native-packager, running stage task"
      sbt stage || echo "[NIX_BUILD_DEBUG] Stage task failed or not available"
    fi
  '';

  installPhase = ''
    mkdir -p $out
    copied_any=0

    copy_stage_root() {
      local dir="$1"
      if [ -d "$dir" ]; then
        echo "[NIX_BUILD_DEBUG] Copying staged application from $dir"
        cp -r "$dir"/. $out/
        copied_any=1
      fi
    }

    copy_stage_root "target/universal/stage"

    for stageDir in $(find . -path '*/target/universal/stage' -type d); do
      if [ "$stageDir" != "target/universal/stage" ]; then
        copy_stage_root "$stageDir"
      fi
    done

    if [ $copied_any -eq 0 ]; then
      echo "[NIX_BUILD_DEBUG] No staged artifacts found. Failing build."
      exit 1
    fi
  '';

  fixupPhase = ''
    echo "[NIX_BUILD_DEBUG] Verifying bin directory:"
    ls -la $out/bin

    # Fix paths in shell scripts
    patchShebangs $out/bin
  '';
}

