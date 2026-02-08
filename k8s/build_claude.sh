#!/bin/bash
# Claude Code agent – Job-only image (no persistent K8s Deployment)
$(dirname "$0")/build_image.sh "jervis-claude" "backend/service-claude/Dockerfile" ":backend:service-claude"
