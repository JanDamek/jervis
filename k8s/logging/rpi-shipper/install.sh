#!/usr/bin/env bash
# =============================================================================
# Install/refresh the UniFi controller log shipper on the RPi.
# Idempotent: stops existing container, syncs configs, starts new one.
#
# Usage on RPi:
#   sudo /opt/unifi-shipper/install.sh
#
# Or remote (from a machine with SSH access):
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

echo "Aggregator target: ${AGGREGATOR_HOST}:${AGGREGATOR_PORT}"
echo "Host log dir:      ${LOG_HOST_PATH}"

# Ensure config dir exists with our files
if [[ ! -f "${DIR}/fluent-bit.conf" ]]; then
  echo "ERROR: ${DIR}/fluent-bit.conf missing — copy rpi-shipper/* into ${DIR} first"
  exit 1
fi

# Pre-pull image (so swap is fast)
docker pull "${IMAGE}"

# Stop+remove old container if any
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
  -v "${DIR}/fluent-bit.conf:/fluent-bit/etc/fluent-bit.conf:ro" \
  -v "${DIR}/parsers.conf:/fluent-bit/etc/parsers.conf:ro" \
  -p 127.0.0.1:2020:2020 \
  "${IMAGE}"

echo
echo "Status:"
docker ps --filter "name=${NAME}" --format '  {{.Names}} | {{.Status}}'
echo
echo "Logs (first 20 lines):"
sleep 2
docker logs "${NAME}" --tail=20 2>&1 || true
echo
echo "Health: curl -s http://127.0.0.1:2020/api/v1/health"
