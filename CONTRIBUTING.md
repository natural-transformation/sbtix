## Testing

### Scripted

`sbt scripted` runs the scripted tests. Note that for the `sbtix/private-auth` test you will need to have the `./serve-authenticated.py` server running. For example, `python3 serve-authenticated.py` in the nix shell. 

To run one example, run `sbt scripted sbtix/simple`.

When you touch `plugin/src/main/resources/sbtix/default.nix.template`, regenerate the fixtures by running, for example:

```bash
(cd plugin/src/sbt-test/sbtix/simple && sbt --error -Dplugin.version=0.4-SNAPSHOT "clean" "genNix" "genComposition")
```

and copying the resulting `default.nix` back into `expected/default.nix`. Repeat for any other scripted test that asserts on `default.nix` (e.g. `sbtix/private-auth`). This keeps the checked-in expectations aligned with the template.

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

#### CI preview

The integration tests aren't Nix builds because they require network access and build access to the Nix store.
To run the tests in a CI-like environment, run for example:

```bash
hci effect run --no-token --as-branch master default.effects.tests.multi-build
```

You can find the tests through tab completion.
