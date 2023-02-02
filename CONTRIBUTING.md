## Testing

### Scripted

`sbt scripted` runs the scripted tests. Note that for the `sbtix/private-auth` test you will need to have the `./serve-authenticated.py` server running.

### Integration

The `tests` directory contains further integration tests. Move
to the `tests` directory and run the appropriate `run.sh` from
there.

```console
nix develop    # load the NIX_PATH and other dependencies
nix shell .    # build sbtix and add it to PATH
./tests/multi-build/run.sh
./tests/template-generation/run.sh
```
