#!/bin/bash
# OpenHands coding engine – Job-only image (no persistent K8s Deployment)
$(dirname "$0")/build_image.sh "jervis-coding-engine" "backend/service-coding-engine/Dockerfile" ":backend:service-coding-engine"
