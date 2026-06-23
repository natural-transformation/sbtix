# This file originates from SBTix
{ runCommand, fetchurl, lib, stdenv, jdk, jre, sbt, writeText, makeWrapper, gawk
, extraPluginRepos ? []
}:
let
    inherit (lib)
      catAttrs
      concatLists
      concatMap
      concatStringsSep
      flatten
      fold
      init
      mapAttrsToList
      optionalString
      splitString
      toLower
      unique
      ;

    sbtTemplate = repoDefs: versioning:
    let
        buildSbt = writeText "build.sbt" ''
          scalaVersion := "${versioning.scalaVersion}"
        '';

        mainScala = writeText "Main.scala" ''
          object Main extends App {
            println("hello nix")
          }
        '';

        buildProperties = writeText "build.properties" ''
          sbt.version=${versioning.sbtVersion}
        '';

        # SBT Launcher Configuration
        # http://www.scala-sbt.org/0.13.5/docs/Launcher/Configuration.html
        sbtixRepos = writeText "sbt-setup-template-repos" ''
        [repositories]
        ${concatStringsSep "\n  " repoDefs}
        '';
    in stdenv.mkDerivation (rec {
            name = "sbt-setup-template";

            dontPatchELF      = true;
            dontStrip         = true;

            # set environment variable to affect all SBT commands
            SBT_OPTS = ''
             -Dsbt.ivy.home=./.ivy2/
             -Dsbt.boot.directory=./.sbt/boot/
             -Dsbt.global.base=./.sbt
             -Dsbt.global.staging=./.staging
             -Dsbt.override.build.repos=true
             -Dsbt.repository.config=${sbtixRepos}
            '';

            unpackPhase = ''
              runHook preUnpack

              ln -s ${buildSbt}  ./build.sbt
              ln -s ${mainScala} ./Main.scala

              mkdir -p ./project

              ln -s ${buildProperties} ./project/build.properties

              runHook postUnpack
            '';

            buildInputs = [ jdk sbt ];

            buildPhase = ''
              runHook preBuild

              ${sbtixShellHelpers}
              sbtix_setup_local_sbt_home
              sbtix_sbt update

              runHook postBuild
            '';

            installPhase =''
              runHook preInstall

              mkdir -p $out
              # Copy the hidden ivy lock files. Only keep ivy cache folder, not ivy local. local might be empty now but I want to be sure it is not polluted in the future.
              rm -rf ./.ivy2/local
              cp -r --remove-destination ./.ivy2 $out/ivy
              cp -r --remove-destination ./.sbt $out/sbt

              runHook postInstall
            '';
    });

  mergeSbtTemplates = templates: runCommand "merge-sbt-template" {}
        (let
            copyTemplate = template:
                [ "cp -rns ${template}/ivy $out"
                  "cp -rns ${template}/sbt $out"
                  "chmod -R u+rw $out"
                ];
        in
            concatStringsSep "\n" (["mkdir -p $out"] ++ concatLists (map copyTemplate templates))
        );

  copyFile = name: input:
    runCommand name { inherit input; } ''
      cp -a $input $out
    '';

  sbtixShellHelpers = ''
    sbtix_sbt() {
      sbt --server "$@"
    }

    sbtix_setup_local_sbt_home() {
      localHome="$(pwd)/.sbt-home"
      localCache="$localHome/.cache"
      mkdir -p "$localHome" "$localCache"
      export SBT_OPTS="''${SBT_OPTS:-} -Duser.home=$localHome"
      export HOME="$localHome"
      export XDG_CACHE_HOME="$localCache"
    }

    sbtix_seed_sbt_boot() {
      if [ -d ${sbt}/share/sbt/boot ]; then
        echo "[SBTIX_NIX_DEBUG] Seeding sbt boot from ${sbt}/share/sbt/boot"
        mkdir -p ./.sbt-boot
        cp -RL ${sbt}/share/sbt/boot/. ./.sbt-boot/
        chmod -R u+w ./.sbt-boot || true
      fi
    }

    sbtix_prepare_sbt() {
      sbtix_setup_local_sbt_home
      sbtix_seed_sbt_boot
    }

    sbtix_copy_stage_roots() {
      local output="$1"
      local stageDir
      local found=1

      while IFS= read -r stageDir; do
        echo "[SBTIX_NIX_DEBUG] Copying staged application from $stageDir"
        cp -r "$stageDir"/. "$output"/
        found=0
      done < <(
        find . -type d \
          \( -path '*/target/universal/stage' -o -path '*/target/out/*/universal/stage' \) \
          ! -path '*/streams/*' \
          | sort -u
      )

      return "$found"
    }

    sbtix_stage_program() {
      local output="$1"
      shift
      local stage_command
      local stage_succeeded=0

      for stage_command in "$@"; do
        echo "[SBTIX_NIX_DEBUG] Running stage command: $stage_command"
        if sbtix_sbt "$stage_command"; then
          stage_succeeded=1
          if sbtix_copy_stage_roots "$output"; then
            return 0
          fi
        else
          echo "[SBTIX_NIX_DEBUG] Stage command failed: $stage_command" 1>&2
        fi
      done

      if [ "$stage_succeeded" -eq 0 ]; then
        echo "error: buildSbtProgram could not run any configured stage command." 1>&2
      else
        echo "error: buildSbtProgram did not find staged application output." 1>&2
      fi
      echo "expected: a stage command creates target/universal/stage or target/out/.../universal/stage, usually via sbt-native-packager." 1>&2
      echo "if this project is a library, use sbtix.buildSbtLibrary instead." 1>&2
      echo "for custom layouts, use sbtix.buildSbtProject with an installPhase." 1>&2
      return 1
    }
  '';

in rec {
    mkRepo = name: artifacts: localBuildsRepo: runCommand name {}
        (let
            parentDirs = filePath:
                concatStringsSep "/" (init (splitString "/" filePath));
            synthesizeMavenMetadata = ''
              for repoRoot in "$out"/*; do
                [ -d "$repoRoot" ] || continue
                [ "$(basename "$repoRoot")" != "cas" ] || continue
                find "$repoRoot" -type d | while IFS= read -r moduleDir; do
                  module="$(basename "$moduleDir")"
                  versions="$(
                    for versionDir in "$moduleDir"/*; do
                      [ -d "$versionDir" ] || continue
                      version="$(basename "$versionDir")"
                      if find "$versionDir" \( -type f -o -type l \) \( -name "$module-$version.*" -o -name "$module-$version-*.*" \) -print -quit | grep -q .; then
                        printf '%s\n' "$version"
                      fi
                    done | sort -u
                  )"
                  [ -n "$versions" ] || continue

                  relative="''${moduleDir#"$repoRoot"/}"
                  groupPath="$(dirname "$relative")"
                  if [ "$groupPath" = "." ]; then
                    groupId=""
                  else
                    groupId="$(printf '%s' "$groupPath" | tr '/' '.')"
                  fi

                  metadataFile="$moduleDir/maven-metadata.xml"
                  {
                    printf '%s\n' '<metadata>'
                    [ -z "$groupId" ] || printf '  <groupId>%s</groupId>\n' "$groupId"
                    printf '  <artifactId>%s</artifactId>\n' "$module"
                    printf '%s\n' '  <versioning>'
                    printf '%s\n' '    <versions>'
                    printf '%s\n' "$versions" | while IFS= read -r version; do
                      printf '      <version>%s</version>\n' "$version"
                    done
                    printf '%s\n' '    </versions>'
                    printf '%s\n' '  </versioning>'
                    printf '%s\n' '</metadata>'
                  } > "$metadataFile"
                done
              done
            '';
            linkArtifact = outputPath: urlAttrs:
                let
                  artifact =
                    if urlAttrs.type or null == "built" then
                      if localBuildsRepo == "" then
                        abort "'sbtixBuildInputs' parameter missing from 'buildSbtProgram'/'buildSbtLibrary', but local dependencies found."
                      else
                        # Unlike fetched JARs which are content addressed derivations by virtue of being fixed-output,
                        # these copies are not content addressed, because ca-derivations is still experimental.
                        # This is unfortunate, because it will create duplication in the store during builds and development,
                        # but at least these can be GC-ed and allow the end result to only reference the single JARs that
                        # result from this copying operation.
                        copyFile (baseNameOf urlAttrs.path) (localBuildsRepo + "/" + urlAttrs.path)
                    else
                      # Use standard sha256 attribute
                      fetchurl { url = urlAttrs.url; sha256 = urlAttrs.sha256; };
                  hashBash =
                    if urlAttrs.type or null == "built"
                    then ''$(sha256sum "${artifact}" | cut -c -64)''
                    # Use sha256 directly
                    else ''$(echo ${toLower urlAttrs.sha256} | tr / _)'';

                  # Extract if this is a POM file
                  isPom = (builtins.match ".*\\.pom$" outputPath) != null;
                  
                  # For POM files, we need to create symlinks in both the parent directory and artifact path
                  pomLinks = if isPom then [
                    ''ln -fsn "${artifact}" "$out/${outputPath}"''
                  ] else [];
                in
                [ ''mkdir -p "$out/${parentDirs outputPath}"''
                  ''ln -fsn "${artifact}" "$out/${outputPath}"''
                  # TODO: include the executable bit as a suffix of the hash.
                  #       Shouldn't matter in our use case though.
                  ''ln -fsn "${artifact}" "$out/cas/${hashBash}"''
                ] ++ pomLinks;
        in
            ''
              mkdir -p $out/cas
            '' + concatStringsSep "\n" (concatLists (mapAttrsToList linkArtifact artifacts)) + "\n" + synthesizeMavenMetadata);

    repoConfig = {repos, nixrepo, name}:
        let
            repoPatternOptional = repoPattern:
                optionalString (repoPattern != "") ", ${repoPattern}";
            
            # Add explicit pattern for Maven repos to handle POM files correctly
            mavenPattern = "[organization]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]";
            
            repoPath = repoName: repoPattern:
                let 
                  # Use Maven pattern for public repos
                  pattern = if repoName == "public" 
                           then mavenPattern
                           else repoPattern;
                in
                [ "${name}-${repoName}: file://${nixrepo}/${repoName}/${repoPatternOptional pattern}" ];
        in
            concatLists (mapAttrsToList repoPath repos);

    ivyRepoPattern = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]";

    mergeAttr = attr: repo:
        fold (a: b: a // b) {} (catAttrs attr repo);

    buildSbtProject = args@{repo, name, buildInputs ? [], sbtixBuildInputs ? "", sbtOptions ? "", pluginBootstrap ? "", ...}:
      let
          pluginRepoCandidates = [
            ./sbtix-plugin-repo.nix
            ../sbtix-plugin-repo.nix
          ];
          pluginRepos =
            concatLists (map
              (path: if builtins.pathExists path then [ (import path) ] else [])
              pluginRepoCandidates) ++ extraPluginRepos;
          mergedRepo = repo ++ pluginRepos;
          localBuildsRepo =
            if builtins.typeOf sbtixBuildInputs == "list" then
              abort "Update notice: see the sbtix README to learn how to use 'sbtixBuildInputs'"
            else
              sbtixBuildInputs;
          versionings = unique (flatten (catAttrs "versioning" mergedRepo));
          scalaVersion = (builtins.head versionings).scalaVersion;
          artifacts = mergeAttr "artifacts" mergedRepo;
          repos = mergeAttr "repos" mergedRepo;
          nixrepo = mkRepo "${name}-repo" artifacts localBuildsRepo;

          localRepoEntry = "local";
          remoteDefaults = [
            "maven-central"
            "typesafe-ivy-releases: https://repo.typesafe.com/typesafe/ivy-releases/, ${ivyRepoPattern}"
          ];
          repoDefs = (repoConfig { inherit repos nixrepo name; }) ++ (
            if (localBuildsRepo == "") then [] else [
              "sbtix-local-maven-dependencies: file://${localBuildsRepo}, ${mavenPattern}"
              "sbtix-local-ivy-dependencies: file://${localBuildsRepo}, ${ivyRepoPattern}"
            ]) ++ [ localRepoEntry ] ++ remoteDefaults;

          # This takes a couple of seconds, but only needs to run when dependencies have changed.
          # It's probably near-instantaneous if done in, say, python.
          combinedCas = runCommand "${name}-cas" {} ''
            mkdir -p $out/cas
            for casRef in ${nixrepo}/cas/*; do
              ln -sf "$(readlink "$casRef")" $out/cas/$(basename "$casRef")
            done
          '';


          # SBT Launcher Configuration
          # http://www.scala-sbt.org/0.13.5/docs/Launcher/Configuration.html
          sbtixRepos = writeText "${name}-repos" ''
            [repositories]
              ${concatStringsSep "\n  " repoDefs}
            '';
            
          # Define Maven pattern for consistency
          mavenPattern = "[organization]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]";

      in stdenv.mkDerivation (rec {
            dontPatchELF      = true;
            dontStrip         = true;
            doDist            = true;

            # COURSIER_CACHE env variable is needed if one wants to use non-sbtix repositories in the below repo list, which is sometimes useful.
            COURSIER_CACHE = "./.cache/coursier/v1";

            # Marker for sbt builds running under the Nix sandbox.
            #
            # Some projects use this to disable tasks that would otherwise try to
            # download tools at build time (e.g. scalafmt downloads its core jars).
            SBTIX_NIX_BUILD = "1";

            # set environment variable to affect all SBT commands
            SBT_OPTS = ''
              -Dsbt.ivy.home=./.ivy2/
              -Dsbt.boot.directory=./.sbt-boot
              -Dsbt.global.base=./.sbt
              -Dsbt.global.staging=./.staging
              -Dsbt.override.build.repos=true
              -Dsbt.repository.config=${sbtixRepos}
              -Dsbt.offline=true
              -Dsbtix.nixBuild=true
              ${sbtOptions}
            '';

            buildPhase = ''
              runHook preBuild

              ${sbtixShellHelpers}
              export COURSIER_CACHE=$(pwd)/.coursier-cache
              baseSbtOpts="''${SBT_OPTS:-}"
              export SBT_OPTS="$baseSbtOpts ${sbtOptions} -Dsbt.ivy.home=./.ivy2-home -Dsbt.boot.directory=./.sbt-boot -Dcoursier.cache=./.coursier-cache"
              sbtix_prepare_sbt

              # Setup local directory for plugin
              ${pluginBootstrap}

              echo "[SBTIX_NIX_DEBUG] localBuildsRepo='${localBuildsRepo}'"
              if [ -n "${localBuildsRepo}" ]; then
                mkdir -p ./.ivy2-home/local
                cp -RL ${localBuildsRepo}/. ./.ivy2-home/local/
                chmod -R u+w ./.ivy2-home/local
                echo "[SBTIX_NIX_DEBUG] Contents of ./.ivy2-home/local (depth 3):"
                find ./.ivy2-home/local -maxdepth 3 -mindepth 1 -print
              else
                echo "[SBTIX_NIX_DEBUG] No localBuildsRepo provided"
              fi

              echo "[SBTIX_NIX_DEBUG] sbtixRepos configuration:"
              cat ${sbtixRepos}

              sbtix_sbt compile
              runHook postBuild
            '';

            distPhase = ''
              runHook preDist

              echo 1>&2 "replacing copies by references..."
              saved=0
              while read file; do
                hash="$(sha256sum "$file" | cut -c -64 | tr / _)"
                entry="${combinedCas}/cas/$hash"
                if [[ -e $entry ]]; then
                  size="$(stat -c%s $file)"
                  echo 1>&2 "replacing $file ($size bytes)"
                  saved=$[saved+size]
                  rm $file
                  ln -s "$(readlink "$entry")" "$file"
                fi
              done < <(find $out -type f)
              echo 1>&2 "saved $[saved/1000] kB"

              runHook postDist
            '';

            # These inputs are only meant for the build process. If they stick
            # around in the outputs, they'd just bloat the user package for no
            # good reason.
            disallowedReferences = [
              combinedCas
              sbtixRepos
              nixrepo
            ]
            ++ lib.optional (localBuildsRepo != "") [
              localBuildsRepo
            ];
        } // args // {
            repo = null;
            buildInputs = [ makeWrapper jdk sbt ] ++ buildInputs;
        });

    buildSbtLibrary = args@{repo, ...}:
      let
        mainRepo = builtins.head repo;
        scalaVersion = (builtins.head mainRepo.versioning).scalaVersion;
      in buildSbtProject ({
        installPhase = ''
          runHook preInstall

          ${sbtixShellHelpers}
          sbtix_prepare_sbt
          sbtix_sbt ++${scalaVersion} publishLocal
          mkdir -p $out/
          localIvyDir="./.ivy2-home/local"
          if [ -d "$localIvyDir" ]; then
            cp -r "$localIvyDir/." $out/
          else
            echo "warning: expected Ivy cache at $localIvyDir but it was not created" 1>&2
          fi

          runHook postInstall
        '';
    } // args);

    buildSbtProgram = args:
      let
        stageCommandsArg =
          if args ? stageCommands then args.stageCommands
          else if args ? stageCommand then args.stageCommand
          else [ "Universal / stage" "stage" ];
        stageCommands =
          if builtins.typeOf stageCommandsArg == "list"
          then stageCommandsArg
          else [ stageCommandsArg ];
        stageCommandsArray = concatStringsSep "\n" (map (cmd: "            ${lib.escapeShellArg cmd}") stageCommands);
        programArgs = builtins.removeAttrs args [ "stageCommand" "stageCommands" ];
      in buildSbtProject ({
        installPhase = ''
          runHook preInstall

          ${sbtixShellHelpers}
          sbtix_prepare_sbt
          mkdir -p $out/

          stage_commands=(
${stageCommandsArray}
          )

          if ! sbtix_stage_program "$out" "''${stage_commands[@]}"; then
            exit 1
          fi
          if [ -d "$out/bin" ]; then
            while IFS= read -r p; do
              wrapProgram "$p" --prefix PATH : ${jre}/bin --prefix PATH : ${gawk}/bin
            done < <(find "$out/bin" -type f -executable)
          fi

          runHook postInstall
        '';
    } // programArgs);
}
