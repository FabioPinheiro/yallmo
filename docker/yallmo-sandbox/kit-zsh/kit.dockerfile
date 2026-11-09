ARG YALLMO_IMAGE
FROM ${YALLMO_IMAGE} AS zsh-build
USER root
# Build against the workload image; don't assume Zsh is preinstalled. Export only
# new/updated package files, not a replacement dpkg database or the whole base OS.
# Force byte ordering: sort/comm can otherwise disagree under UTF-8 locales.
RUN dpkg-query -W -f='${binary:Package}\t${Version}\n' | LC_ALL=C sort > /tmp/packages-before
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends zsh
RUN set -eu; \
    dpkg-query -W -f='${binary:Package}\t${Version}\n' | LC_ALL=C sort > /tmp/packages-after \
    && mkdir -p /out \
    && { printf '%s\n' zsh zsh-common; LC_ALL=C comm -13 /tmp/packages-before /tmp/packages-after | cut -f1; } \
       | LC_ALL=C sort -u | xargs -r dpkg-query -L \
       | while IFS= read -r file; do \
           if [ -f "$file" ] || [ -L "$file" ]; then \
             parent="$(readlink -f "$(dirname "$file")")"; \
             mkdir -p "/out$parent"; \
             cp -a "$file" "/out$parent/"; \
           fi; \
         done
COPY zshrc /tmp/zshrc
RUN /bin/zsh --version \
    && /bin/zsh -n /tmp/zshrc

# A mixin contributes only runtime files; keep the workload's user and entrypoint.
FROM scratch
COPY --from=zsh-build /out/ /
# lifecycle.files creates the agent-owned .zshrc that sources this public config.
COPY zshrc /usr/share/yallmo-zsh/zshrc
