#!/bin/bash
set -e

IMAGES=("server" "joern" "whisper" "tika" "weaviate" "aider" "coding-engine" "atlassian")

for img in "${IMAGES[@]}"; do
  echo "===> Building and pushing jervis-$img..."
  target="runtime-$img"
  docker buildx build --target "$target" -t "registry.damek-soft.eu/jandamek/jervis-$img:latest" --push .
done
