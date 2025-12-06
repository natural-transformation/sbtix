{ version
, metadata ? {
    organization = "se.nullable.sbtix";
    artifact = "sbtix";
    displayName = "sbtix Plugin";
    description = "Locally provided sbtix plugin for Nix build";
  }
, scalaVersion ? "2.12"
, sbtVersion ? "1.0"
, publicationTimestamp ? "0000000000000"
}:

let
  pomXml = ''
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${metadata.organization}</groupId>
  <artifactId>${metadata.artifact}</artifactId>
  <version>${version}</version>
  <name>${metadata.displayName}</name>
  <description>${metadata.description}</description>
  <packaging>jar</packaging>
</project>
'';

  ivyXml = ''
<ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">
  <info organisation="${metadata.organization}"
        module="${metadata.artifact}"
        revision="${version}"
        status="release"
        publication="${publicationTimestamp}"
        e:sbtVersion="${sbtVersion}"
        e:scalaVersion="${scalaVersion}">
    <description>
      ${metadata.description}
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
    <artifact name="${metadata.artifact}" type="jar" ext="jar" conf="compile"/>
  </publications>
  <dependencies></dependencies>
</ivy-module>
'';

in ''
ivyDir="./.ivy2-home/local/${metadata.organization}/${metadata.artifact}/scala_${scalaVersion}/sbt_${sbtVersion}/${version}"
mkdir -p "$ivyDir/jars" "$ivyDir/ivys" "$ivyDir/poms"
if [ -n "${pluginJar:-}" ] && [ -f "${pluginJar}" ]; then
  cp "${pluginJar}" "$ivyDir/jars/${metadata.artifact}.jar"
elif [ -f ./sbtix-plugin-under-test.jar ]; then
  cp ./sbtix-plugin-under-test.jar "$ivyDir/jars/${metadata.artifact}.jar"
else
  echo "sbtix: unable to locate plugin jar. Provide sbtixTool with source metadata or rerun sbtix genComposition." 1>&2
  exit 1
fi
cat <<'POM_EOF' > "$ivyDir/poms/${metadata.artifact}-${version}.pom"
${pomXml}
POM_EOF
cat <<'IVY_EOF' > "$ivyDir/ivys/ivy.xml"
${ivyXml}
IVY_EOF
ln -sf ivy.xml "$ivyDir/ivys/ivy-${version}.xml"
''

