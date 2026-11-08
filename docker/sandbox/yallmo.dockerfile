# Image supplied through the kit's image argument. Keep it rolling; no token/build secret is needed.
ARG YALLMO_IMAGE
FROM ${YALLMO_IMAGE}
USER agent
ENV SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
# sbx starts the workload separately; do not inherit the normal Docker tini entrypoint.
# JVM proxy/CA trust still needs integration testing: these PEM variables do NOT configure JSSE.
WORKDIR /home/agent/workspace
ENTRYPOINT ["/bin/bash"]
CMD []
