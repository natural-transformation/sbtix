#!@shell@
set -euo pipefail

SBT_OPTS=${SBT_OPTS:-}

# Ensure the JVM sees a consistent user home.
#
# Why: Nix shells / CI often set $HOME to a temp directory, but the Java
# `user.home` property can still point at the real account home (or even
# `/var/empty`). sbt (and sbtix) use `user.home` for Ivy local paths, which can
# lead to stale plugin jars being loaded from a different location and trip the
# "plugin jar mismatch" guard during `genComposition`.
#
# Keep `user.home` aligned with $HOME unless the caller intentionally set it.
case "${SBT_OPTS}" in
  *-Duser.home=*) ;;
  *) SBT_OPTS="${SBT_OPTS} -Duser.home=${HOME}" ;;
esac

IVY_LOCAL="${HOME}/.ivy2/local/se.nullable.sbtix"
if [ -d "${IVY_LOCAL}" ]; then
  echo "Deleting any cached sbtix plugins in '~/.ivy'. So the most recent version from nix is used."
  rm -rf "${IVY_LOCAL}"
fi

GLOBAL_DIR="${HOME}/.sbtix"
PLUGIN_DIR="${GLOBAL_DIR}/plugins"
# Force sbt to use a deterministic, user-writable global base so we can drop in
# the sbtix plugin wrapper and avoid hitting system locations like /var/empty.
mkdir -p "${PLUGIN_DIR}"

# Invalidate the compiled global-plugins build when switching sbtix wrappers.
#
# Why: sbt caches the compiled "global plugins" build under:
#   ${PLUGIN_DIR}/project/** and ${PLUGIN_DIR}/target/**
# When sbtix is upgraded/downgraded, the global plugin definition we symlink
# (`sbtix_plugin.sbt`) changes, but sbt may still reuse the old compiled build
# and keep trying to resolve the old sbtix plugin version (e.g. 0.4-SNAPSHOT).
# This is especially likely with Nix store files, which often have an epoch
# mtime that can confuse file-change detection.
PLUGIN_STAMP_FILE="${GLOBAL_DIR}/.sbtix-plugin-stamp"
CURRENT_PLUGIN_STAMP="@plugin@|@pluginJar@"
OLD_PLUGIN_STAMP=""
if [ -f "${PLUGIN_STAMP_FILE}" ]; then
  OLD_PLUGIN_STAMP="$(cat "${PLUGIN_STAMP_FILE}" 2>/dev/null || true)"
fi
if [ "${OLD_PLUGIN_STAMP}" != "${CURRENT_PLUGIN_STAMP}" ]; then
  echo "sbtix: detected a different wrapper/plugin; clearing global plugin build caches"
  rm -rf "${PLUGIN_DIR}/project" "${PLUGIN_DIR}/target"
  printf '%s' "${CURRENT_PLUGIN_STAMP}" > "${PLUGIN_STAMP_FILE}"
fi

# Expose the in-store plugin JAR to any sbt process launched through this
# wrapper so generated nix expressions can reference it directly.
export SBTIX_PLUGIN_JAR_PATH="@pluginJar@"
export SBT_OPTS="$SBT_OPTS -Dsbtix.pluginJarPath=${SBTIX_PLUGIN_JAR_PATH}"

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

# Also purge cached copies of the sbtix plugin to make sure we always load the
# freshly built jar from the nix store. Otherwise Ivy may silently reuse an old
# snapshot, which then misses the latest template fixes.
IVY_CACHE_DIR="${HOME}/.ivy2/cache/se.nullable.sbtix"
if [ -d "${IVY_CACHE_DIR}" ]; then
  echo "Deleting cached sbtix plugin from '~/.ivy2/cache' so the nix-built jar is reloaded."
  rm -rf "${IVY_CACHE_DIR}"
fi

export SBT_OPTS="-Dsbt.global.base=${GLOBAL_DIR}${SBT_OPTS:+ ${SBT_OPTS}}"
echo "Updating ${PLUGIN_DIR}/sbtix_plugin.sbt symlink"
ln -sf "@plugin@" "${PLUGIN_DIR}/sbtix_plugin.sbt"

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

exec @sbt@ "$@"

