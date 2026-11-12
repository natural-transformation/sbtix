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
  test -f "${repo_root}/result/plugin-repo/se.nullable.sbtix/sbtix_sbt2_3/2.0.0/jars/sbtix_sbt2_3.jar"
fi

workroot="$(mktemp -d "${SBTIX_TEST_TMPDIR:-/tmp}/sbtix-sbt2-cli.XXXXXX")"

# The sbt 2 version of the test projects.
sbt_version="2.0.12"

# Set once the running-server test has started its sbt server.
server_project=""

# Run the plain sbt runner (not sbtix) in a project, the way a developer or an
# IDE does. For sbt 2 builds the runner is a thin client that talks to an sbt
# server running in the background.
plain_sbt() {
  local project_dir="$1"
  shift

  (
    cd "${project_dir}"
    export HOME="${project_dir}/home"
    export SBT_OPTS="-Duser.home=${HOME}"
    # Unix socket paths are limited to about 100 characters. Keep the server
    # socket out of the long project path.
    export SBT_GLOBAL_SERVER_DIR="${workroot}/server"
    sbt "$@"
  )
}

cleanup() {
  # Stop the sbt server of the running-server test. `--no-server` only talks to
  # a server that is already up; it never starts one.
  if [[ -n "${server_project}" ]]; then
    plain_sbt "${server_project}" --no-server shutdown || true
  fi
  rm -rf "${workroot}"
}
trap cleanup EXIT

create_project() {
  local name="$1"
  local project_dir="${workroot}/${name}"

  mkdir -p "${project_dir}/project" "${project_dir}/home"
  cat > "${project_dir}/project/build.properties" <<EOF
sbt.version=${sbt_version}
EOF
  cat > "${project_dir}/build.sbt" <<EOF
ThisBuild / scalaVersion := "3.9.0"

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
    grep -q "org/scala-sbt/sbt/${sbt_version}/sbt-${sbt_version}.jar" project/repo.nix
    grep -q "org/scala-sbt/main_3/${sbt_version}/main_3-${sbt_version}.jar" project/repo.nix
  )
}

simple_project="$(create_project "sbtix-sbt2-cli-smoke")"
run_sbtix_gen_nix "${simple_project}"

# sbtix runs sbt without an sbt server. A portfile here would mean that sbtix
# left a server behind, one that loaded plugin files sbtix has already removed.
test ! -e "${simple_project}/project/target/active.json"

# sbtix supports sbt 2.x only. An sbt 1.x build has to be rejected up front,
# with a message that names the problem, and before any file is written.
sbt1_project="$(create_project "sbtix-sbt2-cli-sbt1-build")"
printf 'sbt.version=1.13.1\n' > "${sbt1_project}/project/build.properties"
(
  cd "${sbt1_project}"
  export HOME="${sbt1_project}/home"
  if "${SBTIX_BIN}" genNix 2> sbtix.err; then
    echo "ERROR: sbtix accepted an sbt 1.x build" >&2
    exit 1
  fi
  grep -q 'supports sbt 2.x only' sbtix.err
  test ! -e repo.nix
  test -z "$(find project -name '*.sbt')"
)

# Regression test: an sbt server is already running for the project, for
# example one started by a plain `sbt` command or by an IDE. That server has not
# loaded the sbtix plugin, so sbtix must not send its commands there (the
# server would answer `Not a valid key: genNix`). sbtix must also leave the
# server registered and working.
server_project="$(create_project "sbtix-sbt2-cli-running-server")"
plain_sbt "${server_project}" --client "show name"
server_portfile="${server_project}/project/target/active.json"
server_before="$(cat "${server_portfile}")"

run_sbtix_gen_nix "${server_project}"

test "$(cat "${server_portfile}")" = "${server_before}"
# This only passes when the original server still answers.
plain_sbt "${server_project}" --no-server "show name"

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
