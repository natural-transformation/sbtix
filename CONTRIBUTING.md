## Testing

### Scripted

`sbt scripted` runs the scripted tests. Note that for the `sbtix/private-auth` test you will need to have the `./serve-authenticated.py` server running.

### Integration

The `tests` directory contains further integration tests. Move
to the `tests` directory and run the appropriate `run.sh` from
there.

#### CI preview

The integration tests aren't Nix builds because they require network access and build access to the Nix store.
To run the tests in a CI-like environment, run for example:

```bash
hci effect run --no-token --as-branch master default.effects.tests.multi-build
```

You can find the tests through tab completion.
