{ callPackage, writeText, writeScriptBin, stdenv, runtimeShell, runCommand
, jdk, jre, sbt, selfSourceInfo ? {}
}:
let
  version = "1.2.7";
  versionSnapshotSuffix = "";
  pluginVersion = "${version}${versionSnapshotSuffix}";

  # Prefer flake-provided source info; fall back to environment overrides so
  # dev shells and tests pin the exact working tree used to build this wrapper.
  sourceUrl = "https://github.com/natural-transformation/sbtix";
  sourceRev = selfSourceInfo.rev or (builtins.getEnv "SBTIX_SOURCE_REV");
  sourceNarHash = selfSourceInfo.narHash or (builtins.getEnv "SBTIX_SOURCE_NAR_HASH");
  sourcePath = selfSourceInfo.outPath or (builtins.getEnv "SBTIX_SOURCE_PATH");

  pluginBootstrapSnippet = import ./plugin/nix-exprs/plugin-bootstrap-snippet.nix {
    version = pluginVersion;
  };

  sbtixPluginRepoOrgPath =
    "${sbtixPluginRepo}/plugin-repo/se.nullable.sbtix";

  sbtixPluginJarPathSbt1 =
    "${sbtixPluginRepo}/plugin-repo/se.nullable.sbtix/sbtix/scala_2.12/sbt_1.0/${pluginVersion}/jars/sbtix.jar";

  sbtixPluginJarPathSbt2 =
    "${sbtixPluginRepo}/plugin-repo/se.nullable.sbtix/sbtix_sbt2_3/${pluginVersion}/jars/sbtix_sbt2_3.jar";

  sbtixNix = writeText "sbtix.nix" (builtins.replaceStrings
    [ "\${plugin-version}" "{{PLUGIN_BOOTSTRAP_SNIPPET}}" ]
    [ pluginVersion pluginBootstrapSnippet ]
    (builtins.readFile ./plugin/nix-exprs/sbtix.nix));

  sbtix = callPackage sbtixNix {
    inherit jdk jre sbt;
    extraPluginRepos = [
      (import ./plugin/sbtix-plugin-repo.nix)
    ];
  };

  sbtixPluginRepo = sbtix.buildSbtProject {
        name = "sbtix-plugin";

        src = ./plugin;
        repo = [ (import ./plugin/repo.nix)
                 (import ./plugin/repo-sbt2.nix)
                 (import ./plugin/project/repo.nix)
                 (import ./plugin/nix-exprs/manual-repo.nix)
               ];

        installPhase =''
          sbt +publishLocal
          mkdir -p $out/plugin-repo
          cp -r ./.ivy2-home/local/. $out/plugin-repo/
        '';
  };

  pluginsSbtix = writeText "plugins.sbt" ''
    resolvers += Resolver.file("Sbtix Plugin Repo", file("${sbtixPluginRepo}/plugin-repo"))(Resolver.ivyStylePatterns)

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
      --replace @pluginRepoOrg@ ${sbtixPluginRepoOrgPath} \
      --replace @pluginJarSbt1@ ${sbtixPluginJarPathSbt1} \
      --replace @pluginJarSbt2@ ${sbtixPluginJarPathSbt2} \
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
