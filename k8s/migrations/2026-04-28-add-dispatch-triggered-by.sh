#!/usr/bin/env bash
# 2026-04-28 — PR2b backfill: every persisted `agent_job_records` document
# now requires `dispatchTriggeredBy` (non-null audit invariant) and gains
# `retryCount` (defaults to 0). The persistence factory raises on load if
# either field is missing — historical records must be patched once before
# the new server image observes them.
#
# Idempotent: only documents missing the field are updated. Safe to re-run.
#
# Per memory `feedback-jervis-deploy-via-push.md` deployment is local; this
# script runs against the Mongo pod from the Macbook (kubectl exec).
#
# Usage:
#   k8s/migrations/2026-04-28-add-dispatch-triggered-by.sh
set -euo pipefail

NAMESPACE="${NAMESPACE:-jervis}"
MONGO_DB="${MONGO_DB:-jervis}"

# Pull the connection string from the cluster secret unless caller already
# exported MONGO_URI. Avoids hard-coding credentials in the script (per
# memory `feedback-dns-only-no-raw-ip.md`: DNS, no IPs, no embedded creds).
if [[ -z "${MONGO_URI:-}" ]]; then
  if ! command -v kubectl >/dev/null 2>&1; then
    echo "MONGO_URI not set and kubectl unavailable" >&2
    exit 1
  fi
  MONGO_URI=$(kubectl get secret -n "${NAMESPACE}" jervis-secrets \
    -o jsonpath='{.data.MONGODB_URL}' | base64 --decode)
fi
if [[ -z "${MONGO_URI}" ]]; then
  echo "could not resolve MONGO_URI from jervis-secrets/MONGODB_URL" >&2
  exit 1
fi

# `manual` is the safest legacy default — it implies "no provenance recorded
# beyond the one-time backfill", which lines up with how dispatches were
# created before PR2b.
read -r -d '' SCRIPT <<'JS' || true
const filter = { dispatchTriggeredBy: { $exists: false } };
const update = { $set: { dispatchTriggeredBy: "manual", retryCount: 0 } };
const before = db.agent_job_records.countDocuments(filter);
print("documents missing dispatchTriggeredBy: " + before);
if (before > 0) {
  const res = db.agent_job_records.updateMany(filter, update);
  print("matched=" + res.matchedCount + " modified=" + res.modifiedCount);
} else {
  print("nothing to backfill — exiting clean");
}
JS

if ! command -v mongosh >/dev/null 2>&1; then
  echo "mongosh not found — install (brew install mongosh) or run from a host that has it" >&2
  exit 1
fi

echo "running mongosh against ${MONGO_DB} (URI redacted)"
mongosh "${MONGO_URI}" --quiet --eval "$SCRIPT"

echo "✓ backfill complete"
