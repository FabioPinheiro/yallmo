# Docker & Sandbox

Run commands from the repository root unless noted. Requires Docker Engine/Desktop
with Buildx; the sandbox also needs Docker Sandboxes (`sbx`).

## Start Yallmo

```bash
docker pull fmgp/yallmo:latest
docker run -d --name yallmo -p 127.0.0.1:8080:8080 \
  --mount type=volume,src=yallmo-data,dst=/data fmgp/yallmo:latest
```

Open **http://127.0.0.1:8080**. Without a provider configuration, this is viewer-only.
For chat, see the [provider configuration guide](../docs/CONFIG_AGENT_PROVIDER.md);
mount a private, writable config and pass `server --storage /data --config /config/yallmo.yaml`.
Ordinary Docker login stores OAuth tokens in that config; it is not host-only isolation.
Bind mounts must be accessible to UID **1000**.

```bash
docker logs --tail 50 -f yallmo       # Follow logs
docker stop yallmo                   # Stop
docker start yallmo                  # Start again
docker rm -f yallmo                  # Remove container; data volume survives
```

**Keep port 8080 on loopback: Yallmo has no application authentication.** Never run
two servers against the same storage. To update, pull again, remove the old container,
and repeat the run command with the same volume.

## Build locally

```bash
docker buildx build --pull --load -f docker/Dockerfile --target runtime -t yallmo:local .
docker run --rm -it yallmo:local bash  # Development shell
bash docker/tests/smoke.sh yallmo:local
```

To start your local build, use `yallmo:local` in the start command above.
Add `--platform linux/amd64` or `--platform linux/arm64` to choose an architecture.
The image includes the server, website, Node/npm, Python, Git, SSH client, sbt,
Coursier, Scala CLI and its `scala` alias. Metals, Bloop and Pi are sandbox-kit-only;
no SSH server is installed. The full Scala distribution/offline Maven repository is not bundled.
Both `scala` and `scala-cli` default to Scala **3.10.1**, matching `build.sbt`;
project directives and explicit `--scala` options still take precedence.
Tool launchers keep Coursier and Scala CLI state private to each UID under
`/var/cache/coursier/UID`, including Scala CLI's cache and config. Explicit
`COURSIER_*`, `SCALA_CLI_HOME` and `SCALA_CLI_CONFIG` overrides are respected.
With `docker run --user`, also use a working directory writable by that UID.
Keep `docker/Dockerfile.dockerignore`: it excludes private runtime data from builds.

## Development sandbox (experimental)

The sandbox adds Bash, Metals, Bloop, Pi, `pi-subagents`, `pi-ssh-remote`, Playwright/MCP,
`gh`, `vim` and `htop`. It uses the **published** `fmgp/yallmo:latest`, not your local build.
The Pi mixin explicitly provisions Node/npm from the official `node:latest` image and
refreshes npm before installing Pi and Playwright, so it also supports published
Yallmo images that do not yet include Node/npm.
Metals and Bloop use upstream Coursier installations, without forced dependency versions.
Their reported dependency CVEs remain a kit concern: moving them out of the published
Yallmo image does not patch them. The publication scan covers Yallmo, not the derived kit.
Live sandbox creation and host-proxy authentication still need host validation.

The environment composes three kits:

- `kit-yallmo/` — workload: development tools, Yallmo service manager, workspace
  mounts, shared network/host-credential policy and interactive shell launcher.
- `kit-pi/` — mixin: Pi, extensions, Playwright/MCP, public configuration templates
  and `prepare.py`. Removing it leaves a working Yallmo development shell; also
  remove the `/home/agent/.pi` mount from the environment when omitting this kit.
- `kit-zsh/` — optional mixin: Zsh configuration and private persistent history.

The `pi/`, `workspace/` and `yallmo-workspace/` directories remain user-managed
state beside the kits. No existing profile contents are rewritten by this split.

The Pi launcher defaults to `PI_CLIPBOARD_OSC52=1` so Pi's `/copy` and Ctrl+X send
text to the host terminal clipboard. This requires an OSC 52-capable terminal (such as Ghostty) with
clipboard writes allowed; no `wl-clipboard` installation is needed. In an existing
sandbox, run `export PI_CLIPBOARD_OSC52=1` before launching Pi to apply the workaround
without recreating the sandbox.

On the host, from the repository root:

```bash
python3 docker/yallmo-sandbox/kit-pi/prepare.py # Once: create missing Pi files from local templates
sbx login
sbx secret set openai --oauth       # Once per host
sbx secret set github --command 'gh auth token' # Once per host but requires 'gh auth login'
# sbx secret set github --sandbox yallmo-github # create the token and inserted here
sbx env plan docker/yallmo-sandbox
sbx env run docker/yallmo-sandbox
```

Host mount sources in `sbxenv.yaml` resolve relative to that file, not your shell's
working directory. `workspace: ../..` shares the repository root at its host absolute
path, for example `/Users/fabio/workspace/yallmo`. The kits also declare three fixed
sandbox destinations:

| Host directory (beside `sbxenv.yaml`) | Sandbox path | Purpose |
| --- | --- | --- |
| `./workspace` | `/home/agent/workspace` | `hello.py` and your coding projects; Bash, Pi and Playwright start here |
| `./yallmo-workspace` | `/home/agent/yallmo-workspace` | Yallmo's `server.sh`, `yallmo.yaml`, storage and logs |
| `./pi` | `/home/agent/.pi` | Sandbox-specific Pi profile |

Bash/Pi still start in `/home/agent/workspace`; use
`cd /Users/fabio/workspace/yallmo` to work on the repository (adjust for your host path).
`YALLMO_WORKSPACE` selects the service directory independently of the coding workspace.

**The repository mount exposes the entire checkout**, including any `.env`, local
credentials/data, and `docker/yallmo-sandbox/` (the kit and sandbox-specific Pi profile).
Only enable this workspace for agents you trust with those files. Review changes to
the environment file and kit before running host-side `sbx env` commands again.
`writableEnvFiles` remains disabled; because this environment file is nested inside
the checkout, do not rely on file-level read-only protection as a security boundary.
For stronger isolation, keep the environment, kit and private data outside the checkout.

If you run from `docker/yallmo-sandbox` instead, use `sbx env plan .` and
`sbx env run .`.

Uncomment the GitHub commands if needed; approve its credential request too.
The GitHub token stays on the host; `gh` and HTTPS Git use the sandbox proxy.
Without that binding, `gh` is installed but unauthenticated. Recreate an existing
sandbox to apply the new credential capability.

Approve the host-managed OAuth request, repository workspace and fixed coding,
Yallmo and private Pi mounts.
`kit-pi/prepare.py` creates only missing `auth.json`, `settings.json`, `mcp.json` and
`models.json` in ignored `docker/yallmo-sandbox/pi/agent/` from the local
`kit-pi/templates/`. It never reads or imports a host `~/.pi/agent`, skills, sessions or
model catalog; no host Pi installation is required. Existing profile contents,
including any skills you add manually, are left alone. It also validates that
`auth.json` contains only the public proxy placeholder and sets private permissions.
Git ignores the profile except for `pi/agent/skills/`: edit skills there and use
`git add docker/yallmo-sandbox/pi/agent/skills/` to version them after reviewing
for private content. Ignoring the other files only excludes them from Git; it
never prevents editing them through the bind mount.

This is one-time initialization, not synchronization: edit
`docker/yallmo-sandbox/pi/agent/models.json` directly to change the running sandbox's
`/home/agent/.pi/agent/models.json` through the bind mount. Open `/model` in Pi to
reload the file, then reselect the model (or restart Pi); no `prepare.py` rerun,
image rebuild or sandbox recreation is needed. Templates only seed new profiles;
rerunning setup never overwrites your edits. The custom `sbx-openai` provider does
not inherit built-in Codex model metadata: its Sol limits and thinking levels are
explicitly configured in the live file and the template. Zero token prices are
intentional for this host-managed subscription route, not API pricing estimates.
**Never copy real OAuth credentials into `auth.json`, run Pi
`/login` here, or put secrets in the workspace:** real ChatGPT tokens stay on the host.

Bash opens normally; **Yallmo starts in the background, Pi stays manual**:

```bash
pi
# Alternative Pi provider; requires Docker Model Runner's host TCP API and model:
pi --provider docker-mr --model ai/gemma4:E4B

yallmo-service status               # Process state, local HTTP readiness and log paths
yallmo-service stop                 # Graceful stop; leaves Bash/Pi running
yallmo-service start                # Start again; waits for the local website
yallmo-service restart
yallmo-service logs                 # Last 100 lines of combined stdout/stderr
yallmo-service logs --follow        # Live output; Ctrl-C stops following, NOT Yallmo
yallmo-service logs --supervisor    # Manager diagnostics
```

Open **http://127.0.0.1:8080**; stop any ordinary Docker container using that port first.
Startup waits up to 30 seconds for the local website, not upstream model providers.
Failures leave Bash available: inspect `status` and `logs`. Crashes are not automatically
retried. Reconnecting Bash does not start another server or undo a manual stop.
For foreground debugging, stop the managed service first, then run
`bash /home/agent/yallmo-workspace/server.sh` inside the sandbox.
Port conflicts fail normally; there is no extra storage lock or killing of port owners.

If service logs report `exec: java: not found` but `java --version` works in Bash,
the lifecycle environment is missing Temurin's Java directory. The shared `server.sh`
restores `JAVA_HOME` (default `/opt/java/openjdk`) and prepends its `bin` to `PATH`
inside the managed child; forwarding `PATH` alone is not sufficient. With the current
host-mounted script, run `yallmo-service start` again—no sandbox rebuild is needed.
Shell exports alone do not update an already-running manager's environment.

**Logs live on the host too:** `docker/yallmo-sandbox/yallmo-workspace/logs/yallmo.log`
contains stdout and stderr (10 MB rotation, three backups). `supervisor.log` contains
manager events (5 MB, two backups). They survive restarts; treat logs as private.
Service control sockets and PID files stay inside the sandbox, outside the shared directories.

The kit keeps the sandbox/service running after you exit Bash. To pause the whole
sandbox, run `sbx stop yallmo-sandbox` on the host. To disable boot autostart, set
`YALLMO_AUTOSTART: "0"` in `sbxenv.yaml` before creating/recreating the sandbox;
`yallmo-service start` still works manually. To inspect from another host terminal:

From the repository root:

```bash
sbx env exec docker/yallmo-sandbox -- yallmo-service status
sbx env exec docker/yallmo-sandbox -- yallmo-service logs --follow
```

`docker/yallmo-sandbox/pi/`, `workspace/` and `yallmo-workspace/` persist on the host
and are writable by sandbox processes. Back them up; the original host Pi profile
is not mounted or synced.

### Optional Zsh kit

`docker/yallmo-sandbox/kit-zsh/` is a separate mixin that **installs Zsh**, bundles
`zshrc` at `/usr/share/yallmo-zsh/zshrc`, and declares a private 512 MiB persistent
volume at `/home/agent/.zsh-data`. SBX's `lifecycle.files` feature generates an
agent-owned `/home/agent/.zshrc` that sources the bundled config; `overwrite: false`
preserves an existing `.zshrc` rather than replacing local edits. Those local edits
are sandbox-local, whereas history and completion caches live in the persistent
volume. No host bind mount is needed. The workload still defaults to Bash without
this opt-in.

Select all three kits and Zsh with the following configuration. On the **host**,
update the kit sources in `docker/yallmo-sandbox/sbxenv.yaml` (preserve its other fields):

```yaml
kits:
  - source: ./kit-yallmo
    args:
      image: docker.io/fmgp/yallmo:latest
  - source: ./kit-pi
    args:
      image: docker.io/fmgp/yallmo:latest
  - source: ./kit-zsh
    args:
      image: docker.io/fmgp/yallmo:latest

env:
  YALLMO_SHELL: /bin/zsh
  # Keep the existing YALLMO_* environment entries here too.
```

Use the same image argument for all kits so native libraries stay compatible.
The Bash launcher initializes the existing tool environment and reports service
status before handing off to Zsh. Pi validates its public auth sentinel only when
you launch `pi`; a missing Pi profile never blocks the workload shell. The shell
launcher forwards arguments and sets `SHELL`; it does not change the account's
login shell or start Pi automatically. Run `bash` when you need a Bash session.

Kit/volume additions require sandbox recreation; follow the upgrade procedure below
and back up any sandbox-local work first. Environment files may be protected
read-only inside SBX, so make and review these edits on the host.

Current local SBX releases retain persistent kit volumes across removal/recreation
when the sandbox name, kit source and volume path stay stable. Check your host's
`sbx` version and storage behavior before relying on removal for important data.
A different name gets separate storage. This volume is private state, not a backup.

The old Compose history directory (now `kit-zsh/zsh-data/`) is ignored by Git and
excluded from the Zsh build context. Its history is **not** read or imported automatically. If needed,
migrate it explicitly after reviewing it for secrets. The kit preserves your prompt,
completion and keybinding preferences, expands home paths correctly, and leaves
SBX-managed GitHub credentials untouched.

To return to Bash, set `YALLMO_SHELL: /bin/bash`; removing the optional kit also
requires recreation. Zsh's history remains separate from the host-mounted Pi,
coding and Yallmo workspaces.

### Upgrade an existing sandbox

When upgrading from the old layout, change `./kit` to `./kit-yallmo`, add
`./kit-pi`, and change `./zsh` to `./kit-zsh` in `sbxenv.yaml` **on the host**
before running `sbx env` again. SBX may mount that file read-only inside a running
sandbox. All three kits should use the same image argument, as shown above.
The renamed Zsh kit may receive a new history volume: back up any important
history and sandbox-local `.zshrc` before removal. The host-mounted Pi profile
and workspaces do not move.

Kit/image/mount changes require a fresh sandbox. Stop the old sandbox before moving
its service data. If your old checkout still has service files under `workspace/`,
move `server.sh`, `yallmo.yaml`, `storage/`, and any `logs/` or `debug/` into
`yallmo-workspace/`, preserving their contents. Do not overwrite an existing destination
or leave a second copy of storage in use. Keep `hello.py` and coding projects in
`workspace/`.

Recreate the sandbox to apply workspace/mount changes (this removes sandbox-local files;
back up anything needed first). From the repository root:

```bash
sbx env rm docker/yallmo-sandbox
sbx env plan docker/yallmo-sandbox
sbx env run docker/yallmo-sandbox
```

The host-mounted `workspace/`, `yallmo-workspace/` and `pi/` directories remain on the host.

## Publish to Docker Hub

Requires Docker/Buildx, curl and Trivy. On macOS, install Trivy with
`brew install trivy`. No additional registry CLI is needed. From the repository root:

```bash
docker login --username YOUR_DOCKERHUB_USER
bash docker/publish.sh                          # Both architectures → fmgp/yallmo
bash docker/publish.sh --arch x86_64             # amd64 only
bash docker/publish.sh --arch aarch64            # arm64 only
```

The script builds, smoke-tests and scans every selected image before uploading.
High/critical Trivy findings block publication. Selected images are pushed to
`staging-amd64` / `staging-arm64`, replacing those tags rather than accumulating
per-run upload tags. Each successful run creates one `build-ID` tag combining
the images selected for that run. If only one architecture is selected, the
build tag contains only that image; an older opposite-architecture staging image
is never included.
**Only a successful two-architecture run updates multi-architecture `latest`.**
Single-architecture runs leave it unchanged. Emulated builds can be slow.

Both local publishing and GitHub Actions reload the scanned archives before
`docker push`. Final tags are assembled from immutable digests returned by those
pushes, not by resolving the mutable staging tags again. A failed run can leave
staging partially updated; use `latest` or a successful `build-ID` tag for deployment.
The old `latest-amd64` / `latest-arm64` aliases are no longer updated.

Build IDs currently differ by publisher:

- Local: `build-YYYYMMDDHHMMSS-PID-RANDOM` (UTC build-start timestamp, process ID,
  and Bash random number).
- GitHub Actions: `build-RUN_ID-ATTEMPT` (GitHub workflow run ID and rerun attempt,
  not a timestamp).

`build-*` identifies an automated build, not a semantic release version.

For GitHub Actions, select **Docker images → Run workflow**. It also runs weekly,
Sunday at **05:23 UTC**, for both architectures. Publishing requires `main` and:

- Variables: `DOCKERHUB_USERNAME`, `DOCKERHUB_PUBLISH=true`.
- Secret: `DOCKERHUB_TOKEN` (Docker Hub PAT with Read & Write).

Otherwise, the workflow only builds, tests and scans. Never commit tokens.

## other commands

```bash
sbx exec -it yallmo-sandbox bash # connect

sbx policy allow network --sandbox yallmo-sandbox '**'          # add allow-all rule
sbx policy rm network --sandbox yallmo-sandbox --resource '**'  # remove  allow-all rule
```
