#!/usr/bin/env bash

set -euxo pipefail
cd "$(dirname "$0")"

ensure_local_sbtix_source() {
  if [[ -n "${SBTIX_SOURCE_REV:-}" && -n "${SBTIX_SOURCE_NAR_HASH:-}" ]]; then
    return
  fi

  # The sbtix wrapper (whether coming from ./result or from Nix) is responsible
  # for exporting a consistent sbtix source pin (rev + narHash) when available.
  #
  # Do NOT attempt to synthesize (rev, narHash) here from a working tree: the
  # narHash would include local state (and often `.git`), which does not match
  # the corresponding upstream revision and will cause Nix "NAR hash mismatch"
  # failures when the generated nix tries to fetch from GitHub.
  return
}

ensure_local_sbtix_source

# Drop any cached sbtix plugin bits to avoid stale jars on the classpath.
rm -rf "${HOME}/.sbtix" "${HOME}/.ivy2/cache/se.nullable.sbtix"

# Clean any stale offline bootstrap jars; generation must rely on store-backed plugin.
for j in {one,two,three}/sbtix-plugin-under-test.jar; do
  if test -e "$j"; then
    rm "$j"
  fi
done

# Regenerate composition files as part of the test. These fixtures intentionally
# exercise template evolution, so we want to fail if sbtix would leave an old
# `default.nix` around.
for f in {one,two,three}/default.nix; do
  if test -e "$f"; then
    rm "$f"
  fi
done

# Drop sbt build caches between runs. These caches can hold stale plugin
# classpaths (including older in-store sbtix jars) and make the test flaky.
rm -rf {one,two,three}/project/target {one,two,three}/project/project/target

for f in {one,two,three}/{,project/}*repo.nix; do
    if test -e $f; then
        rm "$f"
    fi
done

repo_root="$(cd ../.. && pwd)"
SBTIX_GEN_ALL2="${SBTIX_GEN_ALL2:-${repo_root}/result/bin/sbtix-gen-all2}"
if [[ ! -x "${SBTIX_GEN_ALL2}" ]]; then
  if command -v sbtix-gen-all2 >/dev/null 2>&1; then
    SBTIX_GEN_ALL2="sbtix-gen-all2"
  else
    echo "ERROR: sbtix-gen-all2 not found (checked '${SBTIX_GEN_ALL2}' and PATH)." >&2
    echo "       Set SBTIX_GEN_ALL2 or build sbtix so '${repo_root}/result/bin/sbtix-gen-all2' exists." >&2
    exit 1
  fi
fi

pushd one
sbt --error publishLocal
"$SBTIX_GEN_ALL2"
# Generated Nix files must not rely on workspace bootstrap jars. The plugin jar
# must always come from the store-backed plugin repo/source block.
if grep -q 'sbtix-plugin-under-test.jar' sbtix-generated.nix; then
  echo "ERROR: one/sbtix-generated.nix references sbtix-plugin-under-test.jar" >&2
  exit 1
fi
grep -Eq 'sbtixSource = /nix/store/|rev = "[0-9a-f]{40}"' sbtix-generated.nix
grep -q 'builtins.readDir' sbtix-generated.nix
grep -q 'scala_2.12/sbt_1.0/${pluginVersion}' sbtix-generated.nix
# Regression guard: sbt-native-packager pulls these plugin transitive deps;
# if they disappear from the generated plugin repo, Nix will try to fetch
# them online and CI will fail. Keep this fast, explicit check to fail early.
grep -q 'junit-bom/5.11.0-M2/junit-bom-5.11.0-M2.pom' project/project/repo.nix
grep -q 'commons-lang3/3.16.0/commons-lang3-3.16.0.pom' project/project/repo.nix
popd

pushd two
sbt --error publishLocal
"$SBTIX_GEN_ALL2"
if grep -q 'sbtix-plugin-under-test.jar' sbtix-generated.nix; then
  echo "ERROR: two/sbtix-generated.nix references sbtix-plugin-under-test.jar" >&2
  exit 1
fi
grep -Eq 'sbtixSource = /nix/store/|rev = "[0-9a-f]{40}"' sbtix-generated.nix
grep -q 'builtins.readDir' sbtix-generated.nix
grep -q 'scala_2.12/sbt_1.0/${pluginVersion}' sbtix-generated.nix
popd

pushd three
"$SBTIX_GEN_ALL2"
if grep -q 'sbtix-plugin-under-test.jar' sbtix-generated.nix; then
  echo "ERROR: three/sbtix-generated.nix references sbtix-plugin-under-test.jar" >&2
  exit 1
fi
grep -Eq 'sbtixSource = /nix/store/|rev = "[0-9a-f]{40}"' sbtix-generated.nix
grep -q 'builtins.readDir' sbtix-generated.nix
grep -q 'scala_2.12/sbt_1.0/${pluginVersion}' sbtix-generated.nix
# Regression guard for sbt core modules: sbt-native-packager pulls
# org.scala-sbt:io_2.12:1.10.4. We seed the sbt boot directory from the
# in-store sbt distribution to avoid any online fetches during Nix builds.
grep -q 'share/sbt/boot' sbtix.nix
grep -q 'org/scala-sbt/io_2.12/1.10.4/io_2.12-1.10.4.pom' project/project/repo.nix
nix-build
./result/bin/mb-three
grep -q 'junit-bom/5.11.0-M2/junit-bom-5.11.0-M2.pom' project/project/repo.nix
grep -q 'commons-lang3/3.16.0/commons-lang3-3.16.0.pom' project/project/repo.nix
popd
