ARG YALLMO_IMAGE
FROM node:latest AS sandbox-node
FROM ${YALLMO_IMAGE} AS pi-build
USER root
# Provision Node/npm even when the published workload image predates them.
COPY --from=sandbox-node /usr/local/bin/node /usr/local/bin/node
COPY --from=sandbox-node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN export HOME=/root XDG_CACHE_HOME=/root/.cache \
    && ln -sf /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sf /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node --version \
    && npm --version \
    && npm install --global --ignore-scripts npm@latest \
    && npm install --global --ignore-scripts @earendil-works/pi-coding-agent@latest \
    && npm install --prefix /opt/pi-extensions --omit=dev --legacy-peer-deps \
       --ignore-scripts --no-audit --no-fund pi-subagents@latest pi-ssh-remote@latest \
    && npm install --prefix /opt/playwright --omit=dev --ignore-scripts --no-audit --no-fund @playwright/mcp@latest \
    && npm cache clean --force \
    && mv /usr/local/bin/pi /usr/local/bin/pi-cli
# Shared read-only browsers; never use root's private browser cache.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
RUN dpkg-query -W -f='${binary:Package}\t${Version}\n' | LC_ALL=C sort > /tmp/packages-before
RUN export HOME=/root XDG_CACHE_HOME=/root/.cache \
    && apt-get update \
    # Use MCP's exact Playwright dependency so browser revisions match. \
    && node /opt/playwright/node_modules/playwright/cli.js install --with-deps \
    && node /opt/playwright/node_modules/@playwright/mcp/cli.js install-browser chrome-for-testing \
    && chmod -R a+rX,go-w /opt/playwright /opt/playwright-browsers
# Like the Zsh mixin, export only new/updated package files, never the dpkg database.
RUN set -eu; \
    dpkg-query -W -f='${binary:Package}\t${Version}\n' | LC_ALL=C sort > /tmp/packages-after \
    && mkdir -p /out \
    && LC_ALL=C comm -13 /tmp/packages-before /tmp/packages-after | cut -f1 \
       | xargs -r dpkg-query -L \
       | while IFS= read -r file; do \
           if [ -f "$file" ] || [ -L "$file" ]; then \
             parent="$(readlink -f "$(dirname "$file")")"; \
             mkdir -p "/out$parent"; \
             cp -a "$file" "/out$parent/"; \
           fi; \
         done
COPY browser-smoke.mjs /opt/playwright/browser-smoke.mjs
# Public templates double as the SDK's offline fixtures; no mounted profile enters the build.
COPY test-provider.mjs /opt/pi-sdk-tests/test-provider.mjs
COPY templates /opt/pi-sdk-tests/templates
COPY --chmod=755 start.sh /usr/local/bin/pi
COPY --chmod=755 playwright.sh /usr/local/bin/playwright
# Preserve launcher symlinks when exporting the mixin.
RUN ln -s playwright /usr/local/bin/playwright-mcp \
    && mkdir -p /out/usr/local/bin \
    && cp -a /usr/local/bin/node /usr/local/bin/npm /usr/local/bin/npx \
       /usr/local/bin/pi /usr/local/bin/pi-cli \
       /usr/local/bin/playwright /usr/local/bin/playwright-mcp /out/usr/local/bin/
USER agent
ENV HOME=/home/agent \
    XDG_CACHE_HOME=/home/agent/.cache \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
WORKDIR /home/agent
# Check the actual installed CLI/SDK and browser permissions without a private profile.
RUN /usr/local/bin/pi-cli --version
RUN node --test /opt/pi-sdk-tests/test-provider.mjs
RUN /usr/local/bin/playwright-mcp --version
RUN node /opt/playwright/browser-smoke.mjs

# Mixins contribute runtime files only, retaining the workload's user and entrypoint.
FROM scratch
COPY --from=pi-build /out/ /
COPY --from=pi-build /usr/local/lib/node_modules /usr/local/lib/node_modules
COPY --from=pi-build /opt/pi-extensions /opt/pi-extensions
COPY --from=pi-build /opt/playwright /opt/playwright
COPY --from=pi-build /opt/playwright-browsers /opt/playwright-browsers
