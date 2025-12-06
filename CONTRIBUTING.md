## Testing

### Scripted

`sbt scripted` runs the scripted tests. Note that for the `sbtix/private-auth` test you will need to have the `./serve-authenticated.py` server running. For example, `python3 serve-authenticated.py` in the nix shell. 

To run one example, run `sbt scripted sbtix/simple`.

When you touch `plugin/src/main/resources/sbtix/default.nix.template`, regenerate the fixtures by running, for example:

```bash
(cd plugin/src/sbt-test/sbtix/simple && sbt --error -Dplugin.version=0.4-SNAPSHOT "clean" "genNix" "genComposition")
```

and copying the resulting `default.nix` back into `expected/default.nix`. Repeat for any other scripted test that asserts on `default.nix` (e.g. `sbtix/private-auth`). This keeps the checked-in expectations aligned with the template.

## Updating the plugin's Nix lockfiles

Whenever you change the plugin's dependencies (Coursier bumps, new libraries, etc.), regenerate the Nix locks under `plugin/` so the sandboxed builds stay reproducible:

```bash
# build the CLI from the current checkout
nix build '.#sbtix'

cd plugin
../result/bin/sbtix genNix          # refreshes plugin/repo.nix and plugin/project/repo.nix
../result/bin/sbtix genComposition  # refreshes plugin/default.nix if needed
../result/bin/sbtix-gen-all2        # produces project/project/repo.nix
```

Commit the updated files (`plugin/repo.nix`, `plugin/project/repo.nix`, `plugin/sbtix-plugin-repo.nix`, and optionally `plugin/default.nix`) along with your dependency changes.

### Integration

The `tests` directory contains the integration suites that validate multi-project builds and template generation. Run them from the repository root after entering the shared nix shell:

```console
nix develop             # loads the sbt/shell tooling for all ziggo projects
nix build '.#sbtix'     # produces ./result/bin/sbtix for the test scripts
export PATH="$PWD/result/bin:$PATH"
./tests/multi-build/run.sh
./tests/template-generation/run.sh
```

Whenever the template or bootstrap snippet changes, rerun the commands above and copy the regenerated `default.nix` (and related `.nix` snippets) from each test directory back into `tests/**`. Those fixtures should always reflect the current generator output rather than manual edits.

#### CI preview

The integration tests aren't Nix builds because they require network access and build access to the Nix store.
To run the tests in a CI-like environment, run for example:

```bash
hci effect run --no-token --as-branch master default.effects.tests.multi-build
```

You can find the tests through tab completion.
