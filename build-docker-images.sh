#!/bin/bash
# Build script for Jervis Docker images
# Usage: ./build-docker-images.sh [image-name] [--no-cache]

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Parse arguments
IMAGE_NAME="${1:-all}"
BUILD_ARGS="${@:2}"

echo -e "${YELLOW}Building Jervis Docker Images${NC}"
echo "Image to build: $IMAGE_NAME"
echo "Build args: $BUILD_ARGS"
echo ""

# Function to build an image
build_image() {
    local target=$1
    local tag=$2

    echo -e "${YELLOW}Building ${tag}...${NC}"
    if docker build --target $target -t $tag $BUILD_ARGS .; then
        echo -e "${GREEN}✓ Successfully built ${tag}${NC}\n"
        return 0
    else
        echo -e "${RED}✗ Failed to build ${tag}${NC}\n"
        return 1
    fi
}

# Build images based on selection
case $IMAGE_NAME in
    "all")
        echo "Building all images..."
        build_image "runtime-server" "jervis-server:latest"
        build_image "runtime-tika" "jervis-tika:latest"
        build_image "runtime-joern" "jervis-joern:latest"
        build_image "runtime-whisper" "jervis-whisper:latest"
        build_image "runtime-weaviate" "jervis-weaviate:latest"
        ;;
    "server")
        build_image "runtime-server" "jervis-server:latest"
        ;;
    "tika")
        build_image "runtime-tika" "jervis-tika:latest"
        ;;
    "joern")
        build_image "runtime-joern" "jervis-joern:latest"
        ;;
    "whisper")
        build_image "runtime-whisper" "jervis-whisper:latest"
        ;;
    "weaviate")
        build_image "runtime-weaviate" "jervis-weaviate:latest"
        ;;
    "builder")
        build_image "builder" "jervis-builder:latest"
        ;;
    *)
        echo -e "${RED}Unknown image: $IMAGE_NAME${NC}"
        echo "Available images: all, server, tika, joern, whisper, weaviate, builder"
        exit 1
        ;;
esac

echo -e "${GREEN}Build process completed!${NC}"
