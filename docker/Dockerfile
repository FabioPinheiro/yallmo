# syntax=docker/dockerfile:1

# Rolling, Ubuntu-based Temurin default (currently JDK 25). This is NOT a Docker Hardened Image.
# `latest` can change the JDK/Ubuntu release: native amd64/arm64 smoke tests are the compatibility gate.
FROM eclipse-temurin:latest AS base
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
USER root
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
       bash ca-certificates curl git jq libatomic1 openssh-client python3 python3-pip python3-venv \
       ripgrep tini unzip xz-utils \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && rm -rf /var/lib/apt/lists/*
# Ubuntu may already reserve UID/GID 1000. Reuse that account, as required by Docker Sandbox.
RUN if getent passwd 1000 >/dev/null; then \
      old_user="$(getent passwd 1000 | cut -d: -f1)"; \
      usermod --login agent --home /home/agent --move-home "$old_user"; \
    else useradd --uid 1000 --user-group --create-home --shell /bin/bash agent; fi \
    && usermod --shell /bin/bash agent \
    && if [[ "$(id -gn agent)" != agent ]]; then groupmod --new-name agent "$(id -gn agent)"; fi \
    && mkdir -p /home/agent/.cache/coursier /home/agent/.sbt /home/agent/.ivy2 /home/agent/.npm \
       /data /config /workspace /opt/yallmo \
    && chown -R agent:agent /home/agent \
    && chown agent:agent /data /config /workspace /opt/yallmo
# Home/cache ownership is established at image build time only; never chown mounted workspaces at startup.
ENV HOME=/home/agent \
    XDG_CACHE_HOME=/home/agent/.cache
WORKDIR /workspace

# Node's rolling official binary is glibc-compatible with this Ubuntu base.
FROM node:latest AS node
FROM base AS toolchain
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
# Security: node:latest can bundle an older npm. npm 11.20.0 contains brace-expansion 5.0.9 and undici 6.28.0,
# responsible for the six reported HIGH findings. Current npm fixes its own bundled dependencies; do not force
# unrelated Pi transitive versions or suppress Trivy. Root's install/cache must stay outside the agent's home.
RUN export HOME=/root XDG_CACHE_HOME=/root/.cache \
    && npm install --global --ignore-scripts npm@latest \
    && npm cache clean --force
COPY docker/install-tools.py /tmp/install-tools.py
# Root installers must not create root-owned files inside the agent's HOME/cache.
RUN HOME=/root XDG_CACHE_HOME=/root/.cache python3 /tmp/install-tools.py && rm /tmp/install-tools.py

FROM toolchain AS build
WORKDIR /build
RUN chown agent:agent /build
# Only source/build inputs enter the context. Never embed secrets in those allowed files.
COPY --chown=agent:agent . .
USER agent
# Fail early on home/cache permissions, before downloading the sbt launcher as the non-root user.
RUN test -w "$HOME" && test -w "$XDG_CACHE_HOME/coursier" \
    && npm --prefix web-harness/vite ci
RUN sbt --server -no-server 'dockerStage'

FROM toolchain AS development
COPY --from=build /build/target/docker/lib /opt/yallmo/lib
COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/yallmo
# No Pi login/auth files are copied. npm and Pi intentionally follow current releases.
RUN export HOME=/root XDG_CACHE_HOME=/root/.cache \
    && npm install --global --ignore-scripts @earendil-works/pi-coding-agent@latest \
    && npm cache clean --force
ENV NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
USER agent
WORKDIR /workspace
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/yallmo"]
CMD ["bash"]

# Default/final target: Python/Bash/Git/SSH client + JVM apps, without Node/Pi/sbt/Scala CLI.
# Use development for Scala CLI; offline scalamode compiler-cache preparation is not implemented yet.
FROM base AS runtime
COPY --from=build /build/target/docker/lib /opt/yallmo/lib
COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/yallmo
USER agent
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/yallmo"]
CMD ["server"]
