# Optional Sandbox kit

These files are **not inputs to `docker/Dockerfile`** and are not embedded in the published images.
The Dockerfile-specific build-context allowlist excludes this folder.

| File | Used by |
|---|---|
| `yallmo.yaml` | `sbx`: declares the v3 shell workload, image argument and development network policy |
| `yallmo.dockerfile` | The Sandbox kit builder: derives a shell workload from the published dev image |

The kit requests no provider credentials. Installing Pi here does not authenticate it.
`USER agent` selects the image's non-root UID 1000; `fmgp` is the Docker Hub owner, not a container user.

For a Compose-like Sandbox definition with a persistent workspace, see
**[`../yallmo-sandbox-sbx-example/`](../yallmo-sandbox-sbx-example/README-SBX-SANDBOX.md)**. It uses `sbx env` and its own local Pi kit, not this shell kit.
That example seeds only a public proxy sentinel in its Sandbox auth.json; real tokens remain host-managed.
Its Pi SDK wire tests pass; live Docker host-proxy validation is pending. Never copy/mount a real host auth.json.
