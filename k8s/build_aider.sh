#!/bin/bash
# Aider coding agent – Job-only image (no persistent K8s Deployment)
$(dirname "$0")/build_image.sh "jervis-aider" "backend/service-aider/Dockerfile" ":backend:service-aider"
