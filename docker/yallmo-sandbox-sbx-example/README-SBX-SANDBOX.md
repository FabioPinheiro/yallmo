# Pi in Docker Sandbox — host-managed ChatGPT OAuth

This is a self-contained, Compose-like `sbxenv.yaml` example. It uses your published
`fmgp/yallmo:dev` image plus a small local Pi kit. **No host auth file or real token is mounted.**
The image does not need rebuilding just to try this configuration.

## Run on your Mac

```bash
cd ~/workspace/yallmo/docker/yallmo-sandbox-sbx-example
sbx login
sbx secret set openai --oauth # skip if your host OpenAI OAuth is already configured
sbx env plan .
sbx env run .
```

Approve the kit's request to use the host OpenAI OAuth service when prompted. Login/refresh and real
token storage stay on the Mac; the host proxy inserts the real bearer only on requests to `chatgpt.com`.
The new Sandbox name `yallmo-pi-oauth` avoids reusing the earlier shell/shared-credential examples.
Do **not** run Pi `/login` or copy a real `auth.json` into this folder.

Pi starts with provider **`sbx-openai`**. The non-secret preferences snapshot currently selects
**`gpt-6.1-sol`**, matching the host Pi profile inspected for this example. Model availability depends on
your account; choose another model available to your subscription before creating the Sandbox:

```bash
sbx env run --env-arg model=YOUR_MODEL_ID .
```

Kit/model/image/mount changes require a fresh Sandbox. Use `--name ANOTHER_NAME` consistently for a
new instance, or review `sbx env rm .` before recreating. Existing Sandbox sessions/state are deleted by removal;
host `workspace/` files are not. An older Sandbox with credential mounts must not be reused.

## Reuse your existing Pi preferences safely

On your Mac, export supported preferences from your existing agent directory:

```bash
python3 import-settings.py "../../../fmgp_sandbox/pi/agent"
# Or use your normal host Pi profile:
python3 import-settings.py ~/.pi/agent/settings.json
```

Run **one** of those commands, then create a fresh Sandbox, for example:

```bash
sbx env run --name yallmo-pi-config .
```

The stdlib-only importer reads **settings.json only** and writes `pi/preferences.json` for the local kit.
It preserves the selected model, supported UI/thinking preferences, and plain `npm:package` references.
The current snapshot includes high thinking, dark theme, and `pi-subagents`, `pi-opensrc`, `pi-ssh-remote`.
Pi installs declared npm packages **inside the Sandbox**, so only import packages you trust. Copying a
package reference does not copy SSH keys, server credentials, package caches or its private host state.

The kit always remaps the provider to `sbx-openai`, preserving the host-only proxy path. An explicit
`--env-arg model=...` overrides the imported model. Preferences changed inside Pi survive normal stops;
new snapshots/kit changes require a fresh Sandbox. Unknown settings, Git/local package sources, custom
themes, resource paths, `enabledModels` provider patterns and extension-specific state are intentionally
not imported. Local skills/prompts/extensions require separate review/porting; this is not a whole-profile clone.

**Never mount `../../../fmgp_sandbox/pi/agent` or `~/.pi` wholesale.** The importer does not read
`auth.json`, `models.json`, sessions, MCP OAuth or SSH files, and it discards credential/command fields.
Your configured `openai-codex` provider cannot be copied unchanged: its native OAuth path decodes JWTs.

## Yallmo server and persistent storage

`workspace/yallmo.yaml` is the sanitized server config: agents `Transcriber`, `Gemma`, `sol` and providers
`docker-mr`, `chatgpt-1`. The original project-root config is unchanged. Saved OAuth values were not copied:
ChatGPT credentials reference the public placeholder environment variables defined in `sbxenv.yaml`.

The workspace includes `storage/`. The helper creates it if missing and starts the server with:

```bash
yallmo server --storage ./storage --config ./yallmo.yaml --log-level info
```

From this example folder on your Mac, run it in your existing Sandbox (use your actual name):

```bash
sbx env exec --name yallmo-pi-gpt6 . -- bash server.sh
```

The helper changes into its workspace directory first. Storage therefore lives in the shared host
`workspace/storage/`, survives Sandbox deletion/recreation, and is Git-ignored except for `.gitkeep`.
Never run two Yallmo servers against the same storage directory. No extra `--workspace` filesystem grant
is added. The environment declares localhost-only port 8080; an existing Sandbox may still need
`sbx ports NAME --publish 127.0.0.1:8080:8080`.

The kit also allows only `localhost:12434` for Model Runner; its proxy maps
`http://host.docker.internal:12434/engines/v1` to your Mac's loopback API. Enable host-side TCP support
in Docker Desktop. Existing Sandboxes need that policy rule added manually or must be recreated.
The `sol` profile additionally needs the new JVM sentinel/proxy support built into the development image;
updating this YAML alone does not update the JARs in an already-published image.

## How the placeholder works

`pi/auth.json` is intentionally checked in and contains only:

```json
{
  "sbx-openai": {
    "type": "api_key",
    "key": "oai-oat01-proxy-managed"
  }
}
```

This is **not a real API key**. `api_key` is Pi's opaque-bearer credential format; the real host
credential is your ChatGPT subscription OAuth token, managed by Docker. The string exactly matches
`oauth.sentinels.accessToken` in `pi/pi.yaml`. No refresh token, expiry or synthetic JWT is needed in Pi.

The kit seeds `/home/agent/.pi/sbx/auth.json` and `models.json` inside the VM. It uses the custom
`sbx-openai` Responses provider, pointed at `https://chatgpt.com/backend-api/codex`, so Pi does not try
to decode the placeholder through its built-in OpenAI/ChatGPT JWT path. Developer instructions and
tools remain in the request; a small top-level instruction satisfies the Codex endpoint's request shape.
Unsupported output-token/cache-retention fields are omitted. Context/output metadata is conservative;
zero cost metadata is a local subscription estimate, not a promise of free provider usage.

The launcher checks `SBX_CRED_OPENAI_MODE=oauth` before running. If it says host OAuth is missing,
finish `sbx secret set openai --oauth` and approve the service binding on your Mac. No API-key fallback
or `passthrough: true` is configured. Credentials still authorize permitted model requests and can consume quota.

## Files and mounts

```text
yallmo-sandbox-sbx-example/
├── sbxenv.yaml          # Environment definition; kept outside the mounted workspace
├── import-settings.py  # Host-only allowlisted settings exporter; never imports auth/models
├── pi/                 # v3 kit, public auth/model/preferences templates, launcher, offline SDK tests
└── workspace/          # Only this project directory is mounted, read-write
    ├── hello.py
    ├── yallmo.yaml      # Sanitized server config; credential values are environment references
    ├── server.sh        # Server launcher with ./storage, ./yallmo.yaml, INFO logging
    └── storage/         # Persistent server data; runtime contents are Git-ignored
```

All relative paths use the environment file's directory. You can copy this **whole folder**, including
`pi/`, elsewhere. Pi state stays inside the VM and survives stops but not deletion; no Pi home is shared.
Do not put `.env` or other secrets in `workspace/`: build ignore rules do not protect workspace mounts.
The ignored legacy `pi-agent/` directory is not mounted and no existing credential files are deleted.

From another Mac terminal in this example folder:

```bash
sbx env exec . -- python hello.py
sbx exec -it yallmo-pi-oauth bash
sbx stop yallmo-pi-oauth
```

## Validation and current limit

Offline tests use the **actual Pi SDK** with mocked Responses streams. They verify that the stored
sentinel resolves, request headers contain only that sentinel, developer instructions survive, and
text/tool calls/tool results round-trip without JWT decoding. The development-image smoke check runs
these tests on each target architecture. Python tests check the kit's OAuth/sentinel/mount contract.

**The live Docker host-proxy/ChatGPT flow is not verified in this coding-agent container** because it
has no Docker daemon/KVM. Your first Mac run must confirm kit creation, credential binding, successful
model calls, proxy CA trust and host refresh/restarts. Do not treat the offline tests as proof of that boundary.
This config is for Pi only; Yallmo's JVM ChatGPT adapter still needs its own proxy-managed mode.

If a prior example exposed real tokens to a Sandbox, removing the mount does not undo that exposure.
Recreate it and revoke that login from the host/provider if you do not trust what ran there.
