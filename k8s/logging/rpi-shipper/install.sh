#!/usr/bin/env bash
# =============================================================================
# Install/refresh the RPi log shipper. Tails:
#   - UniFi controller logs (/opt/unifi-backup/config/log)
#   - Host system logs (/var/log/{syslog,auth,kern})
#   - DNS server stdout (dns-proxy container — separate index)
#   - Other Docker containers' stdout
#
# Generates fluent-bit.conf from .template by substituting current container IDs.
# Re-run after any container is recreated (their IDs change).
#
# Usage on RPi:
#   sudo /opt/unifi-shipper/install.sh
# Remote:
#   scp -r k8s/logging/rpi-shipper damekjan@192.168.100.28:/tmp/
#   ssh damekjan@192.168.100.28 'sudo mkdir -p /opt/unifi-shipper && \
#     sudo cp /tmp/rpi-shipper/* /opt/unifi-shipper/ && \
#     sudo bash /opt/unifi-shipper/install.sh'
# =============================================================================
set -euo pipefail

AGGREGATOR_HOST="${AGGREGATOR_HOST:-192.168.100.170}"
AGGREGATOR_PORT="${AGGREGATOR_PORT:-24224}"
IMAGE="fluent/fluent-bit:2.2.3"
NAME="unifi-log-shipper"
DIR="/opt/unifi-shipper"
LOG_HOST_PATH="/opt/unifi-backup/config/log"

resolve_cid() {
  docker inspect "$1" -f '{{.Id}}' 2>/dev/null || echo ""
}

write_config() {
  local self_cid="$1" dns_cid="$2" controller_cid="$3"
  sed -e "s|__SELF_CID__|${self_cid:-no-self-yet}|g" \
      -e "s|__DNS_CID__|${dns_cid:-no-dns}|g" \
      -e "s|__CONTROLLER_CID__|${controller_cid:-no-controller}|g" \
      "${DIR}/fluent-bit.conf.template" > "${DIR}/fluent-bit.conf"
}

if [[ ! -f "${DIR}/fluent-bit.conf.template" ]]; then
  echo "ERROR: ${DIR}/fluent-bit.conf.template missing — copy rpi-shipper/* into ${DIR} first"
  exit 1
fi

DNS_CID=$(resolve_cid dns-proxy)
CONTROLLER_CID=$(resolve_cid unifi-controller)
PREV_SELF_CID=$(resolve_cid "${NAME}")

echo "Aggregator target: ${AGGREGATOR_HOST}:${AGGREGATOR_PORT}"
echo "Container IDs:     dns=${DNS_CID:0:12} controller=${CONTROLLER_CID:0:12} prev_self=${PREV_SELF_CID:0:12}"

# Pre-pull image
docker pull "${IMAGE}"

# Initial config — exclude previous self so first reload doesn't tail us tailing us
write_config "${PREV_SELF_CID}" "${DNS_CID}" "${CONTROLLER_CID}"

# Stop+remove old
if docker ps -a --format '{{.Names}}' | grep -qx "${NAME}"; then
  echo "Stopping old ${NAME}..."
  docker stop "${NAME}" >/dev/null
  docker rm "${NAME}" >/dev/null
fi

echo "Starting ${NAME}..."
docker run -d \
  --name "${NAME}" \
  --restart unless-stopped \
  -e AGGREGATOR_HOST="${AGGREGATOR_HOST}" \
  -e AGGREGATOR_PORT="${AGGREGATOR_PORT}" \
  -v "${LOG_HOST_PATH}:/unifi-logs:ro" \
  -v "/var/log:/host-var-log:ro" \
  -v "/var/lib/docker/containers:/var-lib-docker-containers:ro" \
  -v "${DIR}/fluent-bit.conf:/fluent-bit/etc/fluent-bit.conf:ro" \
  -v "${DIR}/parsers.conf:/fluent-bit/etc/parsers.conf:ro" \
  -p 127.0.0.1:2020:2020 \
  "${IMAGE}"

# Resolve new self CID and rewrite config so we don't ingest our own stdout
NEW_SELF_CID=$(resolve_cid "${NAME}")
echo "New self_cid: ${NEW_SELF_CID:0:12}"
if [[ -n "${NEW_SELF_CID}" && "${NEW_SELF_CID}" != "${PREV_SELF_CID}" ]]; then
  write_config "${NEW_SELF_CID}" "${DNS_CID}" "${CONTROLLER_CID}"
  echo "Restarting to pick up self exclude..."
  docker restart "${NAME}" >/dev/null
fi

echo
echo "Status:"
docker ps --filter "name=${NAME}" --format '  {{.Names}} | {{.Status}}'
echo
echo "Logs (last 25 lines):"
sleep 2
docker logs "${NAME}" --tail=25 2>&1 || true
echo
echo "Health: curl -s http://127.0.0.1:2020/api/v1/health"
