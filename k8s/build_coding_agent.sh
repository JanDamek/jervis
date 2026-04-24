#!/bin/bash
# jervis-coding-agent — Job-only image spawned by AgentJobDispatcher per
# dispatched AgentJobRecord (CODING flavor). No persistent K8s Deployment,
# no Gradle JAR — image is pure Claude Code CLI + Python entrypoint.
$(dirname "$0")/build_image.sh "jervis-coding-agent" "backend/service-coding-agent/Dockerfile"
