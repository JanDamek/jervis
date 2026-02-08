#!/bin/bash
# Junie coding agent – Job-only image (no persistent K8s Deployment)
$(dirname "$0")/build_image.sh "jervis-junie" "backend/service-junie/Dockerfile" ":backend:service-junie"
