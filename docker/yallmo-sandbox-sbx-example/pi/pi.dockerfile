# Sandbox-only wrapper around the published development image; no full application rebuild required.
ARG YALLMO_IMAGE
FROM ${YALLMO_IMAGE}
USER root
COPY auth.json models.json preferences.json start.sh /opt/pi-sbx/
# Refuse accidental real credentials in the checked-in placeholder file; never print its contents on failure.
RUN jq -e '. == {"sbx-openai":{"type":"api_key","key":"oai-oat01-proxy-managed"}}' /opt/pi-sbx/auth.json >/dev/null \
    && chmod 644 /opt/pi-sbx/auth.json /opt/pi-sbx/models.json /opt/pi-sbx/preferences.json \
    && chmod 755 /opt/pi-sbx/start.sh \
    && mkdir -p /home/agent/.pi/sbx /home/agent/workspace \
    && chown agent:agent /home/agent/.pi /home/agent/.pi/sbx /home/agent/workspace
USER agent
ENV PI_CODING_AGENT_DIR=/home/agent/.pi/sbx \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
WORKDIR /home/agent/workspace
ENTRYPOINT ["/opt/pi-sbx/start.sh"]
CMD []
