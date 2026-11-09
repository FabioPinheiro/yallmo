ARG YALLMO_IMAGE
# Provision the kit's JS runtime even when the published Yallmo image predates Node/npm.
FROM node:latest AS sandbox-node
FROM ${YALLMO_IMAGE}
USER root
COPY --from=sandbox-node /usr/local/bin/node /usr/local/bin/node
COPY --from=sandbox-node /usr/local/lib/node_modules /usr/local/lib/node_modules
# Replace existing launchers too: newer Yallmo images already provide Node/npm.
RUN export HOME=/root XDG_CACHE_HOME=/root/.cache \
    && ln -sf /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sf /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node --version \
    && npm --version \
    && npm install --global --ignore-scripts npm@latest \
    && npm --version \
    && npm cache clean --force
# IDE/build-server tools are kit-only. Keep upstream dependencies, without security overrides.
# Use the base image's public bootstrap cache so agent/other UIDs can seed private copies.
RUN HOME=/root XDG_CACHE_HOME=/usr/share/coursier/bootstrap/xdg \
    COURSIER_CACHE=/usr/share/coursier/bootstrap/cache \
    COURSIER_ARCHIVE_CACHE=/usr/share/coursier/bootstrap/arc \
    COURSIER_JVM_CACHE=/usr/share/coursier/bootstrap/jvm \
    /usr/share/coursier/cs install --dir /usr/share/coursier/bin --jvm system metals bloop \
    && for tool in metals bloop; do \
         ln -sf /usr/local/libexec/coursier-tool.sh "/usr/local/bin/$tool"; \
       done \
    && chmod -R a+rX,go-w /usr/share/coursier
# One shared, read-only browser installation for the agent (never /root/.cache/ms-playwright).
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
COPY browser-smoke.mjs /opt/playwright/browser-smoke.mjs
COPY --chmod=755 service/control.py /opt/yallmo-service/control.py
COPY service/supervisord.conf /opt/yallmo-service/supervisord.conf
# Public sentinel/model fixtures only: never the private mounted Pi profile.
COPY test-provider.mjs /opt/pi-sdk-tests/test-provider.mjs
COPY sdk-fixtures /opt/pi-sdk-tests/sdk-fixtures
RUN export HOME=/root XDG_CACHE_HOME=/root/.cache \
    && apt-get update \
    && apt-get install -y --no-install-recommends htop gh vim supervisor \
    && npm install --global --ignore-scripts @earendil-works/pi-coding-agent@latest \
    && npm install --prefix /opt/pi-extensions --omit=dev --legacy-peer-deps \
       --ignore-scripts --no-audit --no-fund pi-subagents@latest pi-ssh-remote@latest \
    && npm install --prefix /opt/playwright --omit=dev --ignore-scripts --no-audit --no-fund @playwright/mcp@latest \
    # Use MCP's exact Playwright dependency so the browser revisions always match.
    && node /opt/playwright/node_modules/playwright/cli.js install --with-deps \
    && node /opt/playwright/node_modules/@playwright/mcp/cli.js install-browser chrome-for-testing \
    && ln -s /opt/yallmo-service/control.py /usr/local/bin/yallmo-service \
    && ln -s /opt/playwright/node_modules/playwright/cli.js /usr/local/bin/playwright \
    && ln -s /opt/playwright/node_modules/@playwright/mcp/cli.js /usr/local/bin/playwright-mcp \
    && chmod -R a+rX,go-w /opt/playwright /opt/playwright-browsers \
    && mkdir -p /home/agent/.pi /home/agent/workspace /home/agent/yallmo-workspace \
    && chown agent:agent /home/agent/.pi /home/agent/workspace /home/agent/yallmo-workspace \
    && npm cache clean --force \
    && rm -rf /var/lib/apt/lists/* /var/cache/coursier/0
COPY --chmod=755 start.sh /usr/local/bin/yallmo-sandbox-shell
USER agent
# Send Pi copies to the host terminal via OSC 52, even when SBX advertises Wayland.
ENV HOME=/home/agent \
    XDG_CACHE_HOME=/home/agent/.cache \
    YALLMO_AUTOSTART=1 \
    YALLMO_WORKSPACE=/home/agent/yallmo-workspace \
    PI_CODING_AGENT_DIR=/home/agent/.pi/agent \
    PI_CLIPBOARD_OSC52=1 \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt \
    EDITOR=vim
WORKDIR /home/agent/workspace
# Fresh Docker shells initialize agent's private caches via BASH_ENV. Never run these
# inside root's provisioning shell: runuser would inherit root's exported cache paths.
# Separate steps identify a failing check and preserve the expensive installation layers.
RUN test "$(id -u)" = 1000 && test "$HOME" = /home/agent
RUN metals --version
# --help does not start a Bloop server.
RUN bloop --help
RUN pi --version
# Check the actual kit-installed Pi SDK without provider calls, then browser permissions.
RUN node --test /opt/pi-sdk-tests/test-provider.mjs
RUN /usr/local/bin/playwright-mcp --version
RUN node /opt/playwright/browser-smoke.mjs
ENTRYPOINT ["/usr/local/bin/yallmo-sandbox-shell"]
CMD ["-i"]
