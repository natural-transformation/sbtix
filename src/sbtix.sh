#!@shell@

# remove the ivy cache of sbtix so sbt retrieves from the sbtix nix repo.
# without this your version of sbtix may be overriden by the local ivy cache.
echo "Deleting any cached sbtix plugins in '~/.ivy'. So the most recent version from nix is used."
find ~/.ivy2 -name 'se.nullable.sbtix' -type d -exec rm -rf {} \; > /dev/null 2>&1

#the global plugins directory must be writeable
SBTIX_GLBASE_DIR="$HOME/.sbtix"

# if the directory doesn't exist then create it
if ! [ -d "$SBTIX_GLBASE_DIR" ]; then
  echo "Creating $HOME/.sbtix, sbtix global configuration directory"
  mkdir -p "$SBTIX_GLBASE_DIR/plugins"
fi

SBTIX_SETTINGS_FILE="$SBTIX_GLBASE_DIR/sbtix_settings.sbt"
if [ -f $SBTIX_SETTINGS_FILE ]; then
  echo "Resetting $SBTIX_SETTINGS_FILE"
  rm $SBTIX_SETTINGS_FILE
fi
if [ -f sbtix-build-inputs.nix ]; then
  cat >> $SBTIX_SETTINGS_FILE <<EOF
resolvers ++= Seq(
new MavenCache("sbtix-local-dependencies", file("$(nix-build sbtix-build-inputs.nix)")),
Resolver.file("sbtix-local-dependencies-ivy", file("$(nix-build sbtix-build-inputs.nix)"))(Resolver.ivyStylePatterns),
)
EOF
fi

# if sbtix_plugin.sbt is a link or does not exist then update the link. If it is a regular file do not replace it.
SBTIX_PLUGIN_FILE="$SBTIX_GLBASE_DIR/plugins/sbtix_plugin.sbt"
if [ -L "$SBTIX_PLUGIN_FILE" ] || [ ! -f "$SBTIX_PLUGIN_FILE" ]; then
  echo "Updating $SBTIX_PLUGIN_FILE symlink"
  ln -sf @plugin@ "$SBTIX_PLUGIN_FILE"
else
  echo "$SBTIX_PLUGIN_FILE is not a symlink, keeping it intact"
fi

if [ "$SBT_OPTS" != "" ]; then
  echo '$SBT_OPTS is set, unsetting'
  unset -v SBT_OPTS
fi


#the sbt.global.base directory must be writable
@sbt@/bin/sbt -Dsbt.global.base=$SBTIX_GLBASE_DIR "$@"
