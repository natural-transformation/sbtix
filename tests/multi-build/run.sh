#!/usr/bin/env bash

set -euxo pipefail
cd "$(dirname "$0")"

ensure_local_sbtix_source() {
  if [[ -n "${SBTIX_SOURCE_REV:-}" && -n "${SBTIX_SOURCE_NAR_HASH:-}" ]]; then
    return
  fi

  # Auto-export the current sbtix checkout so generated files fetch the exact
  # tree we are testing. Optional in CI, but removes manual setup for dev work.
  if ! command -v git >/dev/null 2>&1 || ! command -v nix >/dev/null 2>&1; then
    echo "git/nix not available; falling back to packaged sbtix metadata" >&2
    return
  fi

  local repo_root
  repo_root="$(git rev-parse --show-toplevel)"

  export SBTIX_SOURCE_URL="${SBTIX_SOURCE_URL:-https://github.com/natural-transformation/sbtix}"
  export SBTIX_SOURCE_REV="${SBTIX_SOURCE_REV:-$(git rev-parse HEAD)}"
  export SBTIX_SOURCE_NAR_HASH="${SBTIX_SOURCE_NAR_HASH:-$(nix hash path --sri "$repo_root")}"
}

ensure_local_sbtix_source

for f in {one,two,three}/{,project/}*repo.nix; do
    if test -e $f; then
        rm "$f"
    fi
done

pushd one
sbt --error publishLocal
sbtix-gen-all2
# Regression guard: sbt-native-packager pulls these plugin transitive deps;
# if they disappear from the generated plugin repo, Nix will try to fetch
# them online and CI will fail. Keep this fast, explicit check to fail early.
grep -q 'junit-bom/5.11.0-M2/junit-bom-5.11.0-M2.pom' project/project/repo.nix
grep -q 'commons-lang3/3.16.0/commons-lang3-3.16.0.pom' project/project/repo.nix
popd

pushd two
sbt --error publishLocal
sbtix-gen-all2
popd

pushd three
sbtix-gen-all2
# Regression guard for sbt core modules: sbt-native-packager pulls
# org.scala-sbt:io_2.12:1.10.4; if it is missing from the generated
# repos, sbt will try to go online during the Nix build.
grep -q 'org/scala-sbt/io_2.12/1.10.4/io_2.12-1.10.4.pom' repo.nix
grep -q 'org/scala-sbt/io_2.12/1.10.4/io_2.12-1.10.4.pom' project/project/repo.nix
nix-build
./result/bin/mb-three
grep -q 'junit-bom/5.11.0-M2/junit-bom-5.11.0-M2.pom' project/project/repo.nix
grep -q 'commons-lang3/3.16.0/commons-lang3-3.16.0.pom' project/project/repo.nix
popd
