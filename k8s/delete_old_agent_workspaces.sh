#!/bin/bash
# =============================================================================
# delete_old_agent_workspaces.sh — PVC cleanup cron for jervis-coding-agent
#
# Scans the shared `jervis-data-pvc` for stale agent-job worktrees and
# deletes them based on their AgentJobRecord state + completedAt.
# Designed to run as a CronJob daily at 03:00 (off-peak) or invoked
# manually for ad-hoc cleanup.
#
# Retention policy (per PR-C3 spec):
#   - DONE:                 24h    after completedAt
#   - ERROR retryCount=3:   7 days after completedAt
#   - CANCELLED:            24h    after completedAt
#   - RUNNING:              never deleted
#   - QUEUED:               never deleted (no workspace yet)
#
# Source of truth for state is the MongoDB `agent_job_records` collection;
# this script does NOT delete a workspace whose job state is RUNNING /
# QUEUED, even if the on-disk mtime is ancient.
#
# Required env:
#   MONGODB_URL   — connection string to the jervis MongoDB (defaults to
#                   the in-cluster service if unset)
#   PVC_MOUNT     — host path or container mount of the PVC
#                   (default: /opt/jervis/data)
#
# Idempotent: re-running is safe; deletes are best-effort and a previous
# partial delete leaves the entry unchanged for the next run.
# =============================================================================
set -euo pipefail

PVC_MOUNT="${PVC_MOUNT:-/opt/jervis/data}"
MONGODB_URL="${MONGODB_URL:-mongodb://mongodb.jervis.svc.cluster.local:27017/jervis}"
DRY_RUN="${DRY_RUN:-false}"

if [ ! -d "$PVC_MOUNT" ]; then
    echo "[cleanup] PVC mount $PVC_MOUNT not found — exiting"
    exit 1
fi

CLIENTS_ROOT="$PVC_MOUNT/clients"
if [ ! -d "$CLIENTS_ROOT" ]; then
    echo "[cleanup] $CLIENTS_ROOT does not exist — nothing to clean"
    exit 0
fi

PVC_MOUNT="$PVC_MOUNT" MONGODB_URL="$MONGODB_URL" DRY_RUN="$DRY_RUN" python3 <<'PYEOF'
"""Walk PVC for agent-job worktrees, decide retention via Mongo state."""
from __future__ import annotations
import datetime
import os
import shutil
import sys
from pathlib import Path

try:
    from pymongo import MongoClient
except ImportError:
    print("[cleanup] pymongo not installed — install with `pip install pymongo`", file=sys.stderr)
    sys.exit(2)


PVC_MOUNT = Path(os.environ["PVC_MOUNT"])
MONGODB_URL = os.environ["MONGODB_URL"]
DRY_RUN = os.environ.get("DRY_RUN", "false").lower() == "true"

# Retention windows.
RETENTION = {
    "DONE": datetime.timedelta(hours=24),
    "CANCELLED": datetime.timedelta(hours=24),
    "ERROR": datetime.timedelta(days=7),
}
NEVER_DELETE_STATES = frozenset({"RUNNING", "QUEUED", "WAITING_USER"})

now = datetime.datetime.now(datetime.timezone.utc)
client = MongoClient(MONGODB_URL)
db = client.get_default_database()
records = db["agent_job_records"]

deleted = 0
kept = 0
orphans = 0

clients_root = PVC_MOUNT / "clients"
for client_dir in sorted(clients_root.iterdir()) if clients_root.is_dir() else []:
    projects_dir = client_dir / "projects"
    if not projects_dir.is_dir():
        continue
    for project_dir in sorted(projects_dir.iterdir()):
        agent_jobs_dir = project_dir / "agent-jobs"
        if not agent_jobs_dir.is_dir():
            continue
        for ws_dir in sorted(agent_jobs_dir.iterdir()):
            if not ws_dir.is_dir():
                continue
            agent_job_id = ws_dir.name
            try:
                from bson import ObjectId
                obj_id = ObjectId(agent_job_id)
            except Exception:
                # Not a valid AgentJobId — leave the directory alone.
                print(f"[cleanup] skip {ws_dir} — not a valid ObjectId")
                continue

            doc = records.find_one({"_id": obj_id})
            if doc is None:
                # No DB record. Treat as orphan; delete unconditionally
                # (can happen if Mongo was restored from an older snapshot
                # while the PVC kept newer worktrees).
                orphans += 1
                if DRY_RUN:
                    print(f"[cleanup] orphan (would delete) {ws_dir}")
                else:
                    shutil.rmtree(ws_dir, ignore_errors=True)
                    print(f"[cleanup] orphan deleted {ws_dir}")
                continue

            state = doc.get("state", "")
            completed_at = doc.get("completedAt")
            if state in NEVER_DELETE_STATES:
                kept += 1
                continue
            if completed_at is None:
                kept += 1
                continue
            if completed_at.tzinfo is None:
                completed_at = completed_at.replace(tzinfo=datetime.timezone.utc)

            window = RETENTION.get(state)
            if window is None:
                kept += 1
                continue
            age = now - completed_at
            if age < window:
                kept += 1
                continue

            if DRY_RUN:
                print(
                    f"[cleanup] would delete {ws_dir} "
                    f"state={state} age={age} window={window}"
                )
            else:
                shutil.rmtree(ws_dir, ignore_errors=True)
                print(
                    f"[cleanup] deleted {ws_dir} "
                    f"state={state} age={age} window={window}"
                )
            deleted += 1

print(
    f"[cleanup] summary | deleted={deleted} kept={kept} orphans={orphans} "
    f"dry_run={DRY_RUN}"
)
PYEOF
