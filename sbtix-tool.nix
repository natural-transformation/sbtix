{ callPackage, writeText, writeScriptBin, stdenv, runtimeShell, runCommand, jdk, jre, sbt }:
let
  version = "0.4";
  versionSnapshotSuffix = "-SNAPSHOT";
  pluginVersion = "${version}${versionSnapshotSuffix}";

  pluginBootstrapSnippet = import ./plugin/nix-exprs/plugin-bootstrap-snippet.nix {
    version = pluginVersion;
  };

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
                 (import ./plugin/project/repo.nix)
                 (import ./plugin/nix-exprs/manual-repo.nix)
               ];

        installPhase =''
          sbt publishLocal
          mkdir -p $out/plugin-repo
          cp -r ./.ivy2-home/local/. $out/plugin-repo/
        '';
  };

  pluginsSbtix = writeText "plugins.sbt" ''
    resolvers += Resolver.file("Sbtix Plugin Repo", file("${sbtixPluginRepo}/plugin-repo"))(Resolver.ivyStylePatterns)

    addSbtPlugin("se.nullable.sbtix" % "sbtix" % "${pluginVersion}")
  '';

  sbtixScript = runCommand "sbtix" {
  } ''
    mkdir -p $out/bin
    substitute ${./src/sbtix.sh} $out/bin/sbtix \
      --replace @shell@ ${runtimeShell} \
      --replace @plugin@ ${pluginsSbtix} \
      --replace @sbt@ ${sbt}/bin/sbt \
      ;
    chmod a+x $out/bin/sbtix
  '';

  sbtixGenScript = writeScriptBin "sbtix-gen" ''
    #!${runtimeShell}

    ${sbtixScript}/bin/sbtix genNix
    ${sbtixScript}/bin/sbtix genComposition
  '';

  sbtixGenallScript = writeScriptBin "sbtix-gen-all" ''
    #!${runtimeShell}

    ${sbtixScript}/bin/sbtix genNix "reload plugins" genNix
    ${sbtixScript}/bin/sbtix genComposition
  '';

  sbtixGenall2Script = writeScriptBin "sbtix-gen-all2" ''
    #!${runtimeShell}

    ${sbtixScript}/bin/sbtix genNix "reload plugins" genNix "reload plugins" genNix
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
