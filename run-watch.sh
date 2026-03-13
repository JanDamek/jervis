#!/bin/bash

DEVICE_NAME="Jan – Apple Watch"
WATCH_BUNDLE_ID="eu.dameksoft.jervis.watchkitapp"

echo "=== Building and deploying JervisWatch ==="

# Build the Xcode project for watchOS physical device
echo "Building Xcode project for watchOS..."
xcodebuild -project apps/watchApp/JervisWatch.xcodeproj \
  -scheme JervisWatch \
  -configuration Debug \
  -destination "platform=watchOS,name=$DEVICE_NAME" \
  -derivedDataPath build/watchos \
  clean build

if [ $? -ne 0 ]; then
    echo "Error: Build failed"
    exit 1
fi

echo "Looking for watch device..."
# Find device ID — try multiple parsing approaches for different devicectl output formats
DEVICE_ID=$(xcrun devicectl list devices 2>/dev/null | grep -i "Watch" | head -n 1 | awk '{print $3}')

if [ -z "$DEVICE_ID" ] || [ ${#DEVICE_ID} -lt 20 ]; then
    DEVICE_ID=$(xcrun devicectl list devices 2>/dev/null | grep -i "Watch" | grep -oE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' | head -n 1)
fi

if [ -z "$DEVICE_ID" ] || [ ${#DEVICE_ID} -lt 20 ]; then
    # Try by UDID format (lowercase with dashes)
    DEVICE_ID=$(xcrun devicectl list devices 2>/dev/null | grep -i "Watch" | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -n 1)
fi

if [ -z "$DEVICE_ID" ] || [ ${#DEVICE_ID} -lt 20 ]; then
    echo "Available devices:"
    xcrun devicectl list devices 2>/dev/null
    echo ""
    echo "Error: Could not find Apple Watch device. Ensure it's connected via iPhone."
    echo "The app was built successfully — you can install it from Xcode manually."
    exit 1
fi

echo "Found Watch Device ID: $DEVICE_ID"

echo "Installing app on Apple Watch..."
xcrun devicectl device install app --device "$DEVICE_ID" "build/watchos/Build/Products/Debug-watchos/JervisWatch.app"

if [ $? -eq 0 ]; then
    echo "Launching app..."
    xcrun devicectl device process launch --device "$DEVICE_ID" "$WATCH_BUNDLE_ID"
    echo "=== ✓ JervisWatch deployed ==="
else
    echo "Warning: Install via devicectl failed. Try deploying from Xcode instead."
fi
