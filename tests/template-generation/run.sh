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
  repo_root="$(cd ../.. && pwd)"

  # Read the current git revision without invoking `git`. This keeps the test
  # runner hermetic and avoids relying on a particular git version/config.
  local git_dir
  git_dir="${repo_root}/.git"
  if [[ -f "${git_dir}" ]]; then
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
rm -f ./sbtix-plugin-under-test.jar
rm -f ./default.nix
rm -rf ./project/target ./project/project/target
for f in {,project/}repo.nix; do
  if [[ -e "$f" ]]; then
    rm "$f"
  fi
done

SBTIX_GEN_ALL2="${SBTIX_GEN_ALL2:-../../result/bin/sbtix-gen-all2}"
if [[ ! -x "${SBTIX_GEN_ALL2}" ]]; then
  SBTIX_GEN_ALL2="sbtix-gen-all2"
fi

"$SBTIX_GEN_ALL2"
if grep -q 'sbtix-plugin-under-test.jar' sbtix-generated.nix; then
  echo "ERROR: template-generation/sbtix-generated.nix references sbtix-plugin-under-test.jar" >&2
  exit 1
fi
grep -Eq 'sbtixSource = /nix/store/|rev = "[0-9a-f]{40}"' sbtix-generated.nix
grep -q 'builtins.readDir' sbtix-generated.nix
grep -q 'scala_2.12/sbt_1.0/${pluginVersion}' sbtix-generated.nix
nix-build
