{ callPackage, writeText, writeScriptBin, stdenv, runtimeShell, runCommand
, jdk, jre, sbt, selfSourceInfo ? {}
}:
let
  version = "2.0.0";
  versionSnapshotSuffix = "";
  pluginVersion = "${version}${versionSnapshotSuffix}";

  # Prefer flake-provided source info; fall back to environment overrides so
  # dev shells and tests pin the exact working tree used to build this wrapper.
  sourceUrl = "https://github.com/natural-transformation/sbtix";
  sourceRev = selfSourceInfo.rev or (builtins.getEnv "SBTIX_SOURCE_REV");
  sourceNarHash = selfSourceInfo.narHash or (builtins.getEnv "SBTIX_SOURCE_NAR_HASH");
  sourcePath = selfSourceInfo.outPath or (builtins.getEnv "SBTIX_SOURCE_PATH");

  # sbt 2 publishes plugins as `<name>_sbt<sbt major>_<scala binary>`.
  sbtixPluginArtifact = "sbtix_sbt2_3";

  sbtixPluginJarPath =
    "${sbtixPluginRepo}/plugin-repo/se.nullable.sbtix/${sbtixPluginArtifact}/${pluginVersion}/jars/${sbtixPluginArtifact}.jar";

  sbtix = callPackage ./plugin/nix-exprs/sbtix.nix {
    inherit jdk jre sbt;
  };

  sbtixPluginRepo = sbtix.buildSbtProject {
        name = "sbtix-plugin";

        src = ./plugin;
        repo = [ (import ./plugin/repo.nix)
                 (import ./plugin/project/repo.nix)
                 (import ./plugin/nix-exprs/manual-repo.nix)
               ];

        # `--server` makes the sbt runner start sbt in the foreground. A bare
        # `sbt` would go through the thin client and a background server.
        installPhase =''
          sbt --server publishLocal
          mkdir -p $out/plugin-repo
          cp -r ./.ivy2-home/local/. $out/plugin-repo/
        '';
  };

  # The plugin definition that the `sbtix` wrapper adds to a build while it
  # runs. The wrapper passes the plugin repository as `sbtix.pluginRepo`.
  pluginsSbtix = writeText "plugins.sbt" ''
    val sbtixPluginRepo = sys.props.getOrElse(
      "sbtix.pluginRepo",
      sys.error("This file was left behind by sbtix. It is safe to delete it.")
    )

    // Look in the sbtix plugin repository first. sbt asks ~/.ivy2/local before
    // any other resolver, so without this it would load an sbtix plugin that
    // someone published locally (`sbt publishLocal` in an sbtix checkout)
    // instead of the one that belongs to the running sbtix.
    externalResolvers := Resolver.file("Sbtix Plugin Repo", file(sbtixPluginRepo))(using Resolver.ivyStylePatterns) +: externalResolvers.value

    addSbtPlugin("se.nullable.sbtix" % "sbtix" % "${pluginVersion}")
  '';

  generatedIdePluginCompatSbt = writeText "sbtix-generated-ide-plugin-compat.sbt" ''
    val sbtixGeneratedIdePluginModules = Set(
      "ch.epfl.scala" -> "sbt-bloop",
      "org.scalameta" -> "sbt-metals",
      "com.github.sbt" -> "sbt-jdi-tools",
    )

    libraryDependencies := libraryDependencies.value.filterNot { dep =>
      sbtixGeneratedIdePluginModules.exists { case (organization, name) =>
        dep.organization == organization &&
        (dep.name == name || dep.name.startsWith(name + "_"))
      }
    }

    // sbt 2 plugin builds may also load Metals/Bloop, which brings
    // jsoniter-scala-core_2.13. sbtix uses Scala 2.13-compatible uPickle on
    // the sbt 2 axis, so keep unused jsoniter _3 tooling edges out of the
    // generated IDE plugin resolution graph.
    excludeDependencies += ExclusionRule("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-core_3")

    // Metals' generated sbt-bloop plugin can attempt to run bloopInstall while
    // sbtix is loading recursive meta-builds. During sbtix generation that IDE
    // export is not needed; keeping this command present as a no-op lets the
    // build load and leaves dependency locking to sbtix.
    commands += Command.command("bloopInstall")(identity)
  '';

  sbtixScript = runCommand "sbtix" {
  } ''
    mkdir -p $out/bin
    substitute ${./src/sbtix.sh} $out/bin/sbtix \
      --replace @shell@ ${runtimeShell} \
      --replace @plugin@ ${pluginsSbtix} \
      --replace @generatedIdePluginCompat@ ${generatedIdePluginCompatSbt} \
      --replace @pluginRepo@ ${sbtixPluginRepo}/plugin-repo \
      --replace @pluginJar@ ${sbtixPluginJarPath} \
      --replace @sbt@ ${sbt}/bin/sbt \
      --replace @sourceRev@ "${sourceRev}" \
      --replace @sourceNarHash@ "${sourceNarHash}" \
      --replace @sourceUrl@ "${sourceUrl}" \
      --replace @sourcePath@ "${sourcePath}" \
      ;
    chmod a+x $out/bin/sbtix
  '';

  sbtixGenScript = writeScriptBin "sbtix-gen" ''
    #!${runtimeShell}

    ${sbtixScript}/bin/sbtix genNix
  '';

  sbtixGenallScript = writeScriptBin "sbtix-gen-all" ''
    #!${runtimeShell}

    # Kept for backwards compatibility. Prefer `sbtix-gen-all2` for the full
    # flow including `sbtix-generated.nix`.
    ${sbtixScript}/bin/sbtix genNix
  '';

  sbtixGenall2Script = writeScriptBin "sbtix-gen-all2" ''
    #!${runtimeShell}

    ${sbtixScript}/bin/sbtix genComposition
  '';

in
stdenv.mkDerivation {
  name = "sbtix-${version}";

  src = ./.;

  phases = [ "installPhase" ];

  installPhase =''
    mkdir -p $out/bin
    ln -s ${sbtixScript}/bin/sbtix $out/bin/.
    ln -s ${sbtixGenScript}/bin/sbtix-gen $out/bin/.
    ln -s ${sbtixGenallScript}/bin/sbtix-gen-all $out/bin/.
    ln -s ${sbtixGenall2Script}/bin/sbtix-gen-all2 $out/bin/.
    ln -s ${sbtixPluginRepo}/plugin-repo $out
    ln -s ${pluginsSbtix} $out/sbtix_plugin.sbt
  '';

  meta = {
    mainProgram = "sbtix";
  };
}
