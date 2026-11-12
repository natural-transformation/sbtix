#!@shell@
set -euo pipefail

SBT_OPTS=${SBT_OPTS:-}

detect_project_sbt_version() {
  if [ -f "project/build.properties" ]; then
    sed -n 's/^[[:space:]]*sbt\.version[[:space:]]*=[[:space:]]*\([^[:space:]#]*\).*$/\1/p' \
      "project/build.properties" | head -n 1
  fi
}

# sbtix supports sbt 2.x builds only. A build that does not set `sbt.version`
# runs on the sbt runner's own version, which is sbt 2.
SBTIX_SBT_VERSION="$(detect_project_sbt_version)"
case "${SBTIX_SBT_VERSION}" in
  "" | 2.*)
    ;;
  *)
    echo "sbtix: this build uses sbt ${SBTIX_SBT_VERSION} (project/build.properties), but sbtix 2 supports sbt 2.x only." >&2
    echo "sbtix: use sbtix 1.2.8 for sbt 1.x builds, or move the build to sbt 2." >&2
    exit 1
    ;;
esac

# Ensure the JVM sees a consistent user home.
#
# Why: Nix shells / CI often set $HOME to a temp directory, but the Java
# `user.home` property can still point at the real account home (or even
# `/var/empty`). sbt (and sbtix) use `user.home` for Ivy-local paths, which can
# lead to stale plugin jars being loaded from a different location and trip the
# "plugin jar mismatch" guard during `genComposition`.
#
# Force `user.home` to match $HOME. If the caller already set `-Duser.home=...`,
# appending our value makes the JVM pick up the last one.
SBT_OPTS="${SBT_OPTS} -Duser.home=${HOME}"

# Force sbt to use a deterministic, user-writable global base of its own. This
# keeps personal global sbt plugins and settings out of the generated locks,
# and avoids hitting system locations like /var/empty.
GLOBAL_DIR="${HOME}/.sbtix"
mkdir -p "${GLOBAL_DIR}"

# sbtix 1.x loaded its plugin through a global plugin definition in this
# directory. sbt 2 would still load that stale definition, so remove it.
rm -rf "${GLOBAL_DIR}/plugins" "${GLOBAL_DIR}/.sbtix-plugin-stamp"

# Let sbt find the sbtix plugin at a path that stays the same for every sbtix.
#
# Why: sbt keeps the resolved plugin classpath in the build's `target`
# directory, and it reuses it as long as the plugin version is the same. It
# does not look at where the plugin came from. If the plugin files sat at the
# store path of this sbtix, a build that was last used with another sbtix of
# the same version (for example before a nixpkgs update) would keep loading
# that other plugin jar, or fail when the jar is gone. The link below is the
# fixed path. It points at the plugin repository of this sbtix.
PLUGIN_REPO_LINK="${GLOBAL_DIR}/plugin-repo"
ln -sfn "@pluginRepo@" "${PLUGIN_REPO_LINK}"
export SBT_OPTS="$SBT_OPTS -Dsbtix.pluginRepo=${PLUGIN_REPO_LINK}"

# Expose the in-store plugin JAR to any sbt process launched through this
# wrapper so generated nix expressions can reference it directly.
export SBT_OPTS="$SBT_OPTS -Dsbtix.pluginJarPath=@pluginJar@"

# Prefer the in-store checkout of the sbtix sources that produced this wrapper.
# This keeps generated Nix files fully offline (no GitHub fetch) in CI and in
# local dev shells, even when the working tree is dirty.
if [ -n "@sourcePath@" ]; then
  export SBTIX_SOURCE_PATH="@sourcePath@"
fi

if [ -n "${SBTIX_SOURCE_PATH:-}" ]; then
  export SBT_OPTS="$SBT_OPTS -Dsbtix.sourcePath=$SBTIX_SOURCE_PATH"
fi

# Use an isolated Coursier cache under the sbtix global base so we don't
# accidentally reuse stale sbt plugin artifacts across wrapper rebuilds.
export SBT_OPTS="$SBT_OPTS -Dcoursier.cache=${GLOBAL_DIR}/.coursier-cache"
export COURSIER_CACHE="${GLOBAL_DIR}/.coursier-cache/v1"

export SBT_OPTS="-Dsbt.global.base=${GLOBAL_DIR}${SBT_OPTS:+ ${SBT_OPTS}}"

INJECTED_PLUGIN_FILES=""
CREATED_PLUGIN_DIRS=""

cleanup_injected_plugins() {
  for file in ${INJECTED_PLUGIN_FILES}; do
    if [ -L "${file}" ] || [ -f "${file}" ]; then
      rm -f "${file}"
    fi
  done
  for dir in ${CREATED_PLUGIN_DIRS}; do
    rmdir "${dir}" 2>/dev/null || true
  done
}

inject_sbt_file() {
  dir="$1"
  source="$2"
  name="$3"
  if [ ! -d "${dir}" ]; then
    mkdir -p "${dir}"
    CREATED_PLUGIN_DIRS="${dir} ${CREATED_PLUGIN_DIRS}"
  fi

  file="${dir}/${name}-$$.sbt"
  if [ -e "${file}" ] || [ -L "${file}" ]; then
    echo "sbtix: refusing to overwrite existing temporary plugin file: ${file}" >&2
    exit 1
  fi

  ln -s "${source}" "${file}"
  INJECTED_PLUGIN_FILES="${file} ${INJECTED_PLUGIN_FILES}"
}

inject_project_plugin() {
  inject_sbt_file "$1" "@plugin@" "sbtix-auto-plugin"
}

inject_generated_ide_plugin_compat() {
  inject_sbt_file "$1" "@generatedIdePluginCompat@" "zz-sbtix-generated-ide-plugin-compat"
}

inject_generated_ide_plugin_compat_files() {
  if [ ! -d "project" ]; then
    return
  fi

  metals_dirs="$(find project -name metals.sbt -type f -exec dirname {} \; 2>/dev/null | sort -u || true)"
  for dir in ${metals_dirs}; do
    inject_generated_ide_plugin_compat "${dir}"
  done
}

trap cleanup_injected_plugins EXIT

# sbt 2 does not apply a global plugin to the build, so the sbtix plugin is
# added through temporary files in the build's own plugin directories: one for
# the build, one for its meta-build (`reload plugins`).
inject_generated_ide_plugin_compat_files
inject_project_plugin "project"
inject_project_plugin "project/project"

if [ -n "@sourceRev@" ] && [ -n "@sourceNarHash@" ]; then
  export SBTIX_SOURCE_URL="@sourceUrl@"
  export SBTIX_SOURCE_REV="@sourceRev@"
  export SBTIX_SOURCE_NAR_HASH="@sourceNarHash@"
fi

if [ -n "${SBTIX_SOURCE_REV:-}" ] && [ -n "${SBTIX_SOURCE_NAR_HASH:-}" ]; then
  sourceUrl="${SBTIX_SOURCE_URL:-https://github.com/natural-transformation/sbtix}"
  export SBT_OPTS="$SBT_OPTS -Dsbtix.sourceUrl=$sourceUrl -Dsbtix.sourceRev=$SBTIX_SOURCE_REV -Dsbtix.sourceNarHash=$SBTIX_SOURCE_NAR_HASH"
fi

if [ "$#" -gt 0 ]; then
  case "$1" in
    genNix)
      # Run genNix for the main build, reload into the meta-build to capture
      # plugin dependencies, then return to the main build.
      set -- \
        genNix \
        "reload plugins" \
        genNix \
        "reload return"
      ;;
    genComposition)
      # Ensure both the project and meta-build repos are up-to-date before
      # emitting sbtix-generated.nix.
      set -- \
        genNix \
        "reload plugins" \
        genNix \
        "reload return" \
        genComposition
      ;;
  esac
fi

# Run sbt in a JVM of our own that takes no part in the sbt server protocol.
#
# Why: for sbt 2 builds the sbt runner defaults to the thin client (`sbtn`).
# The client hands the commands to the server recorded in
# `project/target/active.json`. A server that is already running for this
# project was started without the plugin files and options set up above, so
# `genNix` does not exist there. And when no server is running, the client
# starts one that keeps running after this script has removed the plugin files
# it loaded.
#
# - `--server` makes the runner start the sbt JVM in the foreground instead of
#   the thin client. It has to be a runner argument: the runner picks the launch
#   mode before it looks at SBT_OPTS.
# - `-Dsbt.server.autostart=false` keeps that JVM from starting a server of its
#   own. It never writes `project/target/active.json`, so a server that is
#   already running for this project stays reachable, and nothing is left
#   running when sbtix exits. It goes last in SBT_OPTS, so it wins over a value
#   set by the caller.
export SBT_OPTS="${SBT_OPTS} -Dsbt.server.autostart=false"

set +e
@sbt@ --server "$@"
status=$?
set -e
trap - EXIT
cleanup_injected_plugins
exit "${status}"
