# This file originates from SBTix
{ runCommand, fetchurl, lib, stdenv, jdk, jre, sbt, writeText, makeWrapper, gawk }:
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
        local
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

              sbt update

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

in rec {
    mkRepo = name: artifacts: localBuildsRepo: runCommand name {}
        (let
            parentDirs = filePath:
                concatStringsSep "/" (init (splitString "/" filePath));
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
                      fetchurl urlAttrs;
                  hashBash =
                    if urlAttrs.type or null == "built"
                    then ''$(sha256sum "${artifact}" | cut -c -64)''
                    else ''$(echo ${toLower urlAttrs.sha256} | tr / _)'';
                in
                [ ''mkdir -p "$out/${parentDirs outputPath}"''
                  ''ln -fsn "${artifact}" "$out/${outputPath}"''
                  # TODO: include the executable bit as a suffix of the hash.
                  #       Shouldn't matter in our use case though.
                  ''ln -fsn "${artifact}" "$out/cas/${hashBash}"''
                ];
        in
            ''
              mkdir -p $out/cas
            '' + concatStringsSep "\n" (concatLists (mapAttrsToList linkArtifact artifacts)));

    repoConfig = {repos, nixrepo, name}:
        let
            repoPatternOptional = repoPattern:
                optionalString (repoPattern != "") ", ${repoPattern}";
            repoPath = repoName: repoPattern:
                [ "${name}-${repoName}: file://${nixrepo}/${repoName}${repoPatternOptional repoPattern}" ];
        in
            concatLists (mapAttrsToList repoPath repos);

    ivyRepoPattern = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]";

    mergeAttr = attr: repo:
        fold (a: b: a // b) {} (catAttrs attr repo);

    buildSbtProject = args@{repo, name, buildInputs ? [], sbtixBuildInputs ? "", sbtOptions ? "", ...}:
      let
          localBuildsRepo =
            if builtins.typeOf sbtixBuildInputs == "list" then
              abort "Update notice: see the sbtix README to learn how to use 'sbtixBuildInputs'"
            else
              sbtixBuildInputs;
          versionings = unique (flatten (catAttrs "versioning" repo));
          scalaVersion = (builtins.head versionings).scalaVersion;
          artifacts = mergeAttr "artifacts" repo;
          repos = mergeAttr "repos" repo;
          nixrepo = mkRepo "${name}-repo" artifacts localBuildsRepo;

          repoDefs = (repoConfig { inherit repos nixrepo name; }) ++ (
            if (localBuildsRepo == "") then [] else [
              "sbtix-local-maven-dependencies: file://${localBuildsRepo}"
              "sbtix-local-ivy-dependencies: file://${localBuildsRepo}, ${ivyRepoPattern}"
            ]);

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
            local
            '';

      in stdenv.mkDerivation (rec {
            dontPatchELF      = true;
            dontStrip         = true;
            doDist            = true;

            # COURSIER_CACHE env variable is needed if one wants to use non-sbtix repositories in the below repo list, which is sometimes useful.
            COURSIER_CACHE = "./.cache/coursier/v1";

            # set environment variable to affect all SBT commands
            SBT_OPTS = ''
              -Dsbt.ivy.home=./.ivy2/
              -Dsbt.boot.directory=./.sbt/boot/
              -Dsbt.global.base=./.sbt
              -Dsbt.global.staging=./.staging
              -Dsbt.override.build.repos=true
              -Dsbt.repository.config=${sbtixRepos}
              ${sbtOptions}
            '';

            buildPhase = ''
              runHook preBuild

              pwd && sbt ++${scalaVersion} compile

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

          sbt ++${scalaVersion} publishLocal
          mkdir -p $out/
          cp ./.ivy2/local/* $out/ -r

          runHook postInstall
        '';
    } // args);

    buildSbtProgram = args: buildSbtProject ({
        installPhase = ''
          runHook preInstall

          sbt stage
          mkdir -p $out/
          cp target/universal/stage/* $out/ -r
          for p in $(find $out/bin/* -executable); do
            wrapProgram "$p" --prefix PATH : ${jre}/bin --prefix PATH : ${gawk}/bin
          done

          runHook postInstall
        '';
    } // args);
}
