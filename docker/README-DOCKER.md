# Yallmo in Docker — five-minute guide

**First implementation:** packaged server + website, a development image, and an experimental
Docker Sandbox shell and a Pi OAuth example. Five minutes to read; a clean Scala/frontend image build can take much longer.
**Pi's sentinel-only OAuth kit is configured and offline-tested; live host-proxy validation is pending.**
Yallmo's JVM host-only ChatGPT authentication remains unsupported. See the support table below.

## Publish — quick reference

From the repository root, with Docker Desktop running:

```bash
brew install trivy              # once: install the vulnerability scanner
docker login --username fmgp
bash docker/publish.sh
```

The script builds, tests and scans both architectures before publishing `fmgp/yallmo:latest` and `:dev`.
On Apple Silicon, amd64 normally uses slower emulation. No upload starts until every check passes.

**GitHub Actions runs:** on relevant source/Docker changes in pull requests and pushes to `main`, daily at
**05:23 UTC**, and manually from the Actions tab. Pull requests never publish; publishing requires `main`,
`DOCKERHUB_PUBLISH=true`, the username variable and token secret described in section 5.

## 1. Build and start

Run from the repository root with Docker Engine/Desktop and Buildx available:

```bash
docker buildx build --pull --no-cache --load -f docker/Dockerfile --target runtime -t yallmo:latest .
docker run -d --name yallmo -p 127.0.0.1:8080:8080 \
  --mount type=volume,src=yallmo-data,dst=/data yallmo:latest
```

Open **http://127.0.0.1:8080**. This starts the website in **viewer-only mode**, without a provider or agent.
Logs: `docker logs --tail 50 yallmo`. Stop/remove the container: `docker rm -f yallmo`.
The named data volume survives removal. Never run two servers against the same data volume.

**Security:** the application has no authentication. Anyone reaching it can claim a user and, when
configured, run tools and manage provider credentials. Keep the port on host loopback; do not expose it
publicly or assume a user name authenticates a client. Avoid publishing it to an untrusted LAN.

## 2. ChatGPT subscription in ordinary Docker

This is the existing application login flow, **not host-only credential isolation**.

1. Edit the **secret-free** `docker/config.example.yaml` model to one available to your subscription
   (or edit a private copy outside your mounted workspace).
2. Seed a private configuration volume as the container's non-root user:

```bash
docker run --rm -i --mount type=volume,src=yallmo-config,dst=/config \
  yallmo:latest bash -c 'umask 077; cat > /config/yallmo.yaml' < docker/config.example.yaml
```

Run that seed command only for a **new** configuration: repeating it overwrites saved credentials.
Restart with the configured backend:

```bash
docker rm -f yallmo
docker run -d --name yallmo -p 127.0.0.1:8080:8080 \
  --mount type=volume,src=yallmo-data,dst=/data \
  --mount type=volume,src=yallmo-config,dst=/config \
  yallmo:latest server --storage /data --config /config/yallmo.yaml --workspace /workspace
```

In the website's **Providers** panel, log into `chatgpt`, then select `assistant` for chat.
Follow the browser/callback instructions shown by the application; no provider login is done during image build.
**OAuth tokens are saved in the configuration volume and visible to the container.** Protect it and its
backups; never attach it to a Sandbox expecting host-only secrets. The full flow still needs container testing.

The default `/workspace` is an empty container directory. To share files, add an explicit, narrow bind
mount to `/workspace`; use `readonly` if tools should not modify them. Bind mounts must be accessible to
UID **1000**. No automatic recursive chown is performed. Python/Bash tools execute with this user's authority.

## 3. Development image and Sandbox

```bash
docker buildx build --pull --no-cache --load -f docker/Dockerfile --target development -t yallmo:dev .
docker run --rm -it yallmo:dev                    # Bash
docker run --rm -it yallmo:dev pi                 # Pi; not automatically authenticated
```

Development adds current Node/npm, Pi, sbt and Scala CLI. Both images include Python 3 (`python` and
`python3`), pip, venv, Bash, Git and an SSH **client**. Use a venv for pip packages; system Python is
externally managed. There is no SSH server. Prefer Sandbox's built-in SSH integration later.

For a **Compose-like environment file with a persistent workspace**, see
[`yallmo-sandbox-sbx-example/`](yallmo-sandbox-sbx-example/README.md). Its local Pi kit declares host-managed OpenAI OAuth and seeds
only a public proxy sentinel in the Sandbox's `auth.json`. A custom Responses provider avoids Pi's built-in JWT path.
**Never copy/mount a real host `auth.json`. Live proxy/ChatGPT validation is still pending; offline SDK tests pass.**
No rebuild of `fmgp/yallmo:dev` is required for this config: `sbx` builds its small local wrapper kit automatically.

The experimental v3 kit is in `docker/sandbox/`. It launches **Bash**, requests no credentials, and
allows development download domains. Publish your `dev` image first (the Sandbox daemon cannot use a
host-local Docker image). On a supported host with `sbx` installed and virtualization available:

```bash
sbx login
sbx run --name yallmo-dev ./docker/sandbox \
  --kit-arg image=docker.io/fmgp/yallmo:dev
```

Run this from a **clean workspace containing no secrets**: Sandbox mounts your current directory
read-write. Do not mount `.env`, private Yallmo config, or Pi authentication files. The kit has **no provider
network access or OAuth declaration**; do not log in inside it as a workaround for host-only credentials.
Java proxy/CA integration remains untested; setting Node/Python PEM certificate variables does not configure
Java's truststore. Never disable TLS verification.

**Configuration environment references:** string values in `yallmo.yaml`/JSON can use `${NAME}`, including
credential fields. The server reads the exact variable its process receives; `$${NAME}` is literal and missing
variables fail clearly. References remain references on disk. For Sandbox, the kit must actually supply that variable:
`sbx secret set` alone does not export arbitrary OAuth variables. This does **not** fix the JWT-parsing blocker or
justify passing real tokens into the sandbox. See [reference syntax](../docs/CONFIG_AGENT_PROVIDER.md#environment-references).

## 4. What is supported?

“Pending” means not verified, not a claim that the feature is broken.

| Feature | Initial status |
|---|---|
| Packaged JVM server + bundled website | Implemented; test staged JARs locally and images in CI |
| Runtime and development images | Implemented; container execution pending in this agent environment |
| Linux amd64 and arm64 | Native CI build/smoke/scan gates configured; not yet run |
| Bash/Python/Git/SSH client | Included; native image smoke tests provided |
| Pi, Node/npm, sbt, Scala CLI | Development image only; no automatic login |
| ChatGPT subscription, ordinary Docker | Existing login path documented; tokens reside inside config volume; integration pending |
| Pi ChatGPT subscription, host-only Sandbox OAuth | Sentinel/custom-provider kit configured; actual Pi SDK text/tool wire tests pass; live host-proxy check pending |
| Yallmo ChatGPT subscription, host-only Sandbox OAuth | **Not supported yet**: the JVM adapter still parses JWT account metadata |
| Yallmo REPL / JLine JNI in images | **Not supported until interactive/native tests pass**; launcher included |
| Non-interactive/dumb-terminal REPL | **Not supported** by the application |
| Local ONNX RAG | **Not supported initially**; dependency remains packaged, no model bundled |
| `scalamode` | **Not supported initially**; requires prewarming its project-pinned offline compiler cache |
| Sandbox development shell | Experimental scaffold; v3 build/runtime validation pending |
| SSH server | Not installed; future opt-in feature |

ONNX is used only by the local RAG embedding implementation/demo, not ordinary provider calls.
Application/compiler versions are declared in `build.sbt`; security dependency updates and forced overrides have
inline rationale there. OAuth validation is not weakened.

## 5. Security updates, tests and Docker Hub

The rolling base is `eclipse-temurin:latest`, currently Ubuntu-based. It is **not** a Docker Hardened Image.
The build updates apt packages and installs current stable developer tools. npm is refreshed separately with
`npm@latest`: even `node:latest` can bundle an npm release with vulnerable dependencies. A future upstream JDK/OS change
may break compatibility: tests must pass before publication. Ubuntu packages can contain backported security
fixes even when their version looks older than upstream.

The sbt/Scala CLI installer uses public GitHub release pages, not the rate-limited REST API, and verifies the
published SHA-256 for each asset. No GitHub token is needed. Missing/changed release-page checksum metadata stops
the build rather than silently installing an unchecked binary.

`.github/workflows/docker.yml` rebuilds daily and on relevant changes, tests on **native amd64/arm64**,
and blocks publication on high/critical Trivy findings, including unfixed ones. Old containers do not update
themselves: pull the new image and recreate them. Update the host/Docker/Sandbox separately.

### Local publishing notes

Use `bash docker/publish.sh` (see the quick reference above). It builds the current working tree and reuses a dedicated
builder; scan archives are removed, while local images and `build-*` platform tags remain for diagnostics. Tag updates
are sequential, not atomic. An optional argument selects another Docker Hub repository. Login is separate; never pass
a token as a script argument.

### Automated GitHub publishing (optional)

Before enabling Docker Hub publication, create the `yallmo` repository and configure GitHub:

- Repository variable `DOCKERHUB_USERNAME`: `fmgp`.
- Repository secret `DOCKERHUB_TOKEN`: a Docker Hub PAT with **Read & Write**, not Delete.
- Repository variable `DOCKERHUB_PUBLISH`: `true` when ready. Leave unset for build/test/scan only.

**Never paste tokens into chat or commit them.** CI publishes `fmgp/yallmo:latest` and `:dev` as
multi-architecture tags only after all native tests and scans pass. Temporary `build-*` tags retain the exact
tested images; manage their retention in Docker Hub. Existing dependency vulnerabilities may block publication.
For local publication, use `docker/publish.sh` rather than manually pushing an untested single-architecture tag.

Local checks:

```bash
# Optional SDK enables harmless build-context sentinel tests; no Docker daemon is needed for these.
python3 -m pip install --target /tmp/yallmo-docker-tests docker
export PYTHONPATH=/tmp/yallmo-docker-tests
python3 -m unittest discover -s docker -p 'test_*.py' -v
sbt --server -no-server 'dockerStage' # creates target/docker/lib; run npm ci in web-harness/vite first
python3 -m unittest discover -s docker -p 'test_*.py' -v # also exercises the staged JVM server
bash docker/smoke.sh yallmo:latest
bash docker/smoke.sh yallmo:dev development
```

Implementation lives under **`docker/`**: Dockerfile, source-only build-context rules, launch/tool installers,
configuration example, tests, smoke checks and the Sandbox kit. `build.sbt` contains the small distribution task;
GitHub requires the scheduled workflow under `.github/workflows/`. The build context must be the repository root.
The context excludes runtime/config/cache files; do not put secrets into source/resource directories.

**Current environment limitation:** Docker/Buildx/sbx clients are installed, but there is no Docker daemon/socket
or `/dev/kvm` exposed here. Full image builds and local Sandbox authentication cannot be claimed as tested.
