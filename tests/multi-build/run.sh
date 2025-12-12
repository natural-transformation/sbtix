#!/usr/bin/env bash

set -euxo pipefail
cd "$(dirname "$0")"

ensure_local_sbtix_source() {
  if [[ -n "${SBTIX_SOURCE_REV:-}" && -n "${SBTIX_SOURCE_NAR_HASH:-}" ]]; then
    return
  fi

  # Auto-export the current sbtix checkout so generated files fetch the exact
  # tree we are testing. Optional in CI, but removes manual setup for dev work.
  if ! command -v nix >/dev/null 2>&1; then
    echo "nix not available; falling back to packaged sbtix metadata" >&2
    return
  fi

  local repo_root
  # `run.sh` lives under `tests/multi-build/`, so the sbtix checkout root is two
  # levels up.
  repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

  # Read the current git revision without invoking `git`. This keeps the test
  # runner hermetic and avoids relying on a particular git version/config.
  local git_dir
  git_dir="${repo_root}/.git"
  if [[ -f "${git_dir}" ]]; then
    # Worktree checkout: `.git` is a file containing `gitdir: <path>`.
    local gitdir_pointer
    gitdir_pointer="$(sed -n 's/^gitdir: //p' "${git_dir}")"
    if [[ -z "${gitdir_pointer}" ]]; then
      echo "Unable to resolve gitdir for ${repo_root}; falling back to packaged sbtix metadata" >&2
      return
    fi
    if [[ "${gitdir_pointer}" = /* ]]; then
      git_dir="${gitdir_pointer}"
    else
      git_dir="${repo_root}/${gitdir_pointer}"
    fi
  fi

  if [[ ! -f "${git_dir}/HEAD" ]]; then
    echo "No git HEAD found for ${repo_root}; falling back to packaged sbtix metadata" >&2
    return
  fi

  local head
  head="$(cat "${git_dir}/HEAD")"
  local rev
  rev=""
  if [[ "${head}" =~ ^ref:\ (.*)$ ]]; then
    local ref_path
    ref_path="${BASH_REMATCH[1]}"
    if [[ -f "${git_dir}/${ref_path}" ]]; then
      rev="$(cat "${git_dir}/${ref_path}")"
    elif [[ -f "${git_dir}/packed-refs" ]]; then
      rev="$(awk -v ref="${ref_path}" '$1 !~ /^#/ && $1 !~ /^\^/ && $2 == ref { print $1; exit }' "${git_dir}/packed-refs")"
    fi
  else
    rev="${head}"
  fi

  rev="$(echo -n "${rev}" | tr -d '\n' | tr -d '\r')"
  if [[ -z "${rev}" ]]; then
    echo "Unable to determine git revision for ${repo_root}; falling back to packaged sbtix metadata" >&2
    return
  fi

  export SBTIX_SOURCE_URL="${SBTIX_SOURCE_URL:-https://github.com/natural-transformation/sbtix}"
  export SBTIX_SOURCE_REV="${SBTIX_SOURCE_REV:-${rev}}"
  export SBTIX_SOURCE_NAR_HASH="${SBTIX_SOURCE_NAR_HASH:-$(nix hash path --sri "$repo_root")}"
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

SBTIX_GEN_ALL2=${SBTIX_GEN_ALL2:-../../../result/bin/sbtix-gen-all2}

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
