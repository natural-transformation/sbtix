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
for f in {,project/}repo.nix; do
  if [[ -e "$f" ]]; then
    rm "$f"
  fi
done

sbtix-gen-all2
nix-build
