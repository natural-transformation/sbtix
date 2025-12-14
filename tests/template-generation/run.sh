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
