## Testing

### Scripted

Run the scripted tests via the `sbtix/*` group:
`sbt "scripted sbtix/*"`.

Note that for the `sbtix/private-auth` test you will need to have the `./serve-authenticated.py` server running. For example, `python3 serve-authenticated.py` in the nix shell. 

To run one example, run `sbt scripted sbtix/simple`.

When you touch the templates under `plugin/src/main/resources/sbtix/` (for example `default.nix.template` or `generated.nix.template`), regenerate the fixtures by running, for example:

```bash
# NOTE: Keep `plugin.version` in sync with `plugin/build.sbt` (`version := ...`).
(cd plugin/src/sbt-test/sbtix/simple && sbt --error -Dplugin.version=0.4.1-SNAPSHOT "clean" "genNix" "genComposition")
```

The scripted tests only check in and assert on the locked `repo.nix` files (`expected/repo.nix` and `expected/project-repo.nix`).  
`sbtix-generated.nix` is generated at runtime and is intentionally **not** checked in for scripted tests (it includes store paths and timestamps).  
If you intentionally change the generator output, update the expectations by copying `repo.nix` and `project/repo.nix` from the test directory into `expected/**` for the relevant suite (repeat for `sbtix/private-auth` as needed).  
If you are testing a local sbtix change and want generated Nix files to stay flake-pure-safe, follow “Local sbtix checkouts” below.

## Updating the plugin's Nix lockfiles

Whenever you change the plugin's dependencies (Coursier bumps, new libraries, etc.), regenerate the Nix locks under `plugin/` so the sandboxed builds stay reproducible:

```bash
# build the CLI from the current checkout
nix build '.#sbtix'

cd plugin
../result/bin/sbtix genNix          # refreshes plugin/repo.nix and plugin/project/repo.nix
../result/bin/sbtix genComposition  # refreshes plugin/sbtix-generated.nix and sample default.nix if needed
../result/bin/sbtix-gen-all2        # produces project/project/repo.nix
```

Commit the updated files (`plugin/repo.nix`, `plugin/project/repo.nix`, `plugin/sbtix-plugin-repo.nix`, and optionally `plugin/default.nix`) along with your dependency changes. Use the environment exports only when testing local sbtix changes.

### Integration

The `tests` directory contains the integration suites that validate multi-project builds and template generation. Run them from the repository root after entering the shared nix shell:

```console
nix develop             # loads sbt + CI tooling (hci, nix, etc.) for this repository
nix build '.#sbtix'     # produces ./result/bin/sbtix for the test scripts
export PATH="$PWD/result/bin:$PATH"
./tests/multi-build/run.sh
./tests/template-generation/run.sh
```

Whenever the template or bootstrap snippet changes, rerun the commands above and copy the regenerated `sbtix-generated.nix` (and the example `default.nix` if you removed it) from each test directory back into `tests/**`. Those fixtures should always reflect the current generator output rather than manual edits.

#### CI preview

The integration tests aren't Nix builds because they require network access and build access to the Nix store.
To run the tests in a CI-like environment, run for example:

```bash
hci effect run --no-token --as-branch master default.effects.tests.multi-build
hci effect run --no-token --as-branch master default.effects.tests.template-generation
```

You can find the tests through tab completion.

### Local sbtix checkouts (optional)

Normal development uses the released sbtix build (`nix shell github:natural-transformation/sbtix` or `nix build '.#sbtix'`), so no extra environment is required.  
If you need to exercise unmerged changes and want generated Nix files to be flake-pure-safe, you need a *real* `(url, rev, narHash)` pin that matches what Nix will fetch later.

IMPORTANT: Do **not** set `SBTIX_SOURCE_NAR_HASH` using `nix hash path .` — that hash includes local state (often including `.git` and untracked files) and frequently causes Nix “NAR hash mismatch” errors when the generated Nix tries to fetch from GitHub.

Recommended options:

1. **Use a flake reference** for the sbtix you are testing (best for downstream flakes). For example, push a branch and use `nix shell github:natural-transformation/sbtix/<branch>`.
2. **Copy the pin from flake metadata** when you really need to run from a checkout. Run `nix flake metadata --json` and copy the values of `locked.rev` and `locked.narHash` into the exports below.

```bash
export SBTIX_SOURCE_URL="https://github.com/natural-transformation/sbtix"
export SBTIX_SOURCE_REV="<<git revision>>"
export SBTIX_SOURCE_NAR_HASH="<<narHash (SRI), e.g. sha256-...>>"
```

Skip this section entirely when using published sbtix binaries. The Scripted, lockfile, and integration instructions above only require these exports when you explicitly test local sbtix changes.
