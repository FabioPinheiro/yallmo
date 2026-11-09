ARG YALLMO_IMAGE
FROM ${YALLMO_IMAGE}
USER root
# IDE/build-server tools stay in the development workload, not the published image.
RUN HOME=/root XDG_CACHE_HOME=/usr/share/coursier/bootstrap/xdg \
    COURSIER_CACHE=/usr/share/coursier/bootstrap/cache \
    COURSIER_ARCHIVE_CACHE=/usr/share/coursier/bootstrap/arc \
    COURSIER_JVM_CACHE=/usr/share/coursier/bootstrap/jvm \
    /usr/share/coursier/cs install --dir /usr/share/coursier/bin --jvm system metals bloop \
    && for tool in metals bloop; do \
         ln -sf /usr/local/libexec/coursier-tool.sh "/usr/local/bin/$tool"; \
       done \
    && chmod -R a+rX,go-w /usr/share/coursier
COPY --chmod=755 service/control.py /opt/yallmo-service/control.py
COPY service/supervisord.conf /opt/yallmo-service/supervisord.conf
RUN apt-get update \
    && apt-get install -y --no-install-recommends htop gh vim supervisor \
    && ln -s /opt/yallmo-service/control.py /usr/local/bin/yallmo-service \
    && mkdir -p /home/agent/workspace /home/agent/yallmo-workspace \
    && chown agent:agent /home/agent/workspace /home/agent/yallmo-workspace \
    && rm -rf /var/lib/apt/lists/* /var/cache/coursier/0
COPY --chmod=755 start.sh /usr/local/bin/yallmo-sandbox-shell
USER agent
ENV HOME=/home/agent \
    XDG_CACHE_HOME=/home/agent/.cache \
    YALLMO_AUTOSTART=1 \
    YALLMO_WORKSPACE=/home/agent/yallmo-workspace \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt \
    EDITOR=vim
WORKDIR /home/agent/workspace
# Fresh agent steps initialize private caches via BASH_ENV, not root's build environment.
RUN test "$(id -u)" = 1000 && test "$HOME" = /home/agent
RUN metals --version
# --help does not start a Bloop server.
RUN bloop --help
ENTRYPOINT ["/usr/local/bin/yallmo-sandbox-shell"]
CMD ["-i"]
