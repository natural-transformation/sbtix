#!@shell@
set -euo pipefail

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

exec @sbt@ "$@"

