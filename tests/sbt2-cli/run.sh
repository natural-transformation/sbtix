#!/usr/bin/env bash

set -euxo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

SBTIX_BIN="${SBTIX_BIN:-${repo_root}/result/bin/sbtix}"
if [[ ! -x "${SBTIX_BIN}" ]]; then
  if command -v sbtix >/dev/null 2>&1; then
    SBTIX_BIN="sbtix"
  else
    echo "ERROR: sbtix not found (checked '${SBTIX_BIN}' and PATH)." >&2
    echo "       Build the CLI first with: nix build '.#sbtix'" >&2
    exit 1
  fi
fi

if [[ -d "${repo_root}/result/plugin-repo" ]]; then
  test -f "${repo_root}/result/plugin-repo/se.nullable.sbtix/sbtix_sbt2_3/1.2.7/jars/sbtix_sbt2_3.jar"
fi

workroot="$(mktemp -d "${SBTIX_TEST_TMPDIR:-/tmp}/sbtix-sbt2-cli.XXXXXX")"
trap 'rm -rf "${workroot}"' EXIT

create_project() {
  local name="$1"
  local project_dir="${workroot}/${name}"

  mkdir -p "${project_dir}/project" "${project_dir}/home"
  cat > "${project_dir}/project/build.properties" <<'EOF'
sbt.version=2.0.0
EOF
  cat > "${project_dir}/build.sbt" <<EOF
ThisBuild / scalaVersion := "3.7.4"

lazy val root = (project in file("."))
  .settings(
    name := "${name}"
  )
EOF

  printf '%s\n' "${project_dir}"
}

run_sbtix_gen_nix() {
  local project_dir="$1"

  (
    cd "${project_dir}"
    export HOME="${project_dir}/home"
    "${SBTIX_BIN}" --error "show sbtVersion"
    "${SBTIX_BIN}" genNix

    test -f repo.nix
    test -f project/repo.nix
    grep -q 'org/scala-sbt/sbt/2.0.0/sbt-2.0.0.jar' project/repo.nix
    grep -q 'org/scala-sbt/main_3/2.0.0/main_3-2.0.0.jar' project/repo.nix
  )
}

simple_project="$(create_project "sbtix-sbt2-cli-smoke")"
run_sbtix_gen_nix "${simple_project}"

sbt_plugin_project="$(create_project "sbtix-sbt2-cli-with-sbt-plugin")"
cat > "${sbt_plugin_project}/project/plugins.sbt" <<'EOF'
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
EOF

run_sbtix_gen_nix "${sbt_plugin_project}"

# Generated Metals/Bloop plugin files are common in developer checkouts. Under
# sbt 2, the wrapper must load sbtix in the build and meta-build while
# coexisting with Metals' recursive plugin files.
metals_project="$(create_project "sbtix-sbt2-cli-with-metals")"
cat > "${metals_project}/project/plugins.sbt" <<'EOF'
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
EOF

for metals_file in \
  "${metals_project}/project/metals.sbt" \
  "${metals_project}/project/project/metals.sbt" \
  "${metals_project}/project/project/project/metals.sbt"; do
  mkdir -p "$(dirname "${metals_file}")"
  cat > "${metals_file}" <<'EOF'
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "2.0.19")
EOF
done

# Some Metals-generated plugin files can point at plugins that do not publish
# sbt 2 artifacts. These files are not needed while sbtix is locking the build.
unsupported_bloop_file="${metals_project}/project/project/project/project/metals.sbt"
mkdir -p "$(dirname "${unsupported_bloop_file}")"
cat > "${unsupported_bloop_file}" <<'EOF'
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "2.0.17")
EOF

unsupported_metals_file="${metals_project}/project/project/project/project/project/metals.sbt"
mkdir -p "$(dirname "${unsupported_metals_file}")"
cat > "${unsupported_metals_file}" <<'EOF'
addSbtPlugin("org.scalameta" % "sbt-metals" % "1.5.1")
addSbtPlugin("com.github.sbt" % "sbt-jdi-tools" % "1.2.0")
EOF

run_sbtix_gen_nix "${metals_project}"
