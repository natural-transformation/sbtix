{ pkgs ? import <nixpkgs> {} }:

let
  fakeSbt = pkgs.writeShellScriptBin "sbt" ''
    set -eu

    echo "fake sbt: $*" 1>&2
    if [ "''${1:-}" != "--server" ]; then
      echo "fake sbt expected --server launcher argument" 1>&2
      exit 42
    fi

    shift
    case "$*" in
      compile)
        mkdir -p target/out/jvm/scala-2.13.18/sbt2-program-layout/classes
        ;;
      "Universal / stage")
        stageDir="target/out/jvm/scala-2.13.18/sbt2-program-layout/universal/stage"
        mkdir -p "$stageDir/bin" "$stageDir/lib"
        cat > "$stageDir/bin/sbt2-program-layout" <<'EOF'
#!/usr/bin/env sh
echo staged-from-target-out
EOF
        chmod +x "$stageDir/bin/sbt2-program-layout"
        echo "fake jar" > "$stageDir/lib/sbt2-program-layout.jar"
        ;;
      stage)
        echo "plain stage intentionally left no target/universal/stage output" 1>&2
        ;;
      *)
        echo "unexpected fake sbt command: $*" 1>&2
        exit 43
        ;;
    esac
  '';

  sbtix = pkgs.callPackage ./sbtix.nix {
    sbt = fakeSbt;
  };
in
  sbtix.buildSbtProgram {
    name = "sbt2-program-layout";
    src = ./.;
    repo = [];
  }
