#!/bin/bash

# Build the iOS framework
echo "Building iOS framework..."
./gradlew :apps:mobile:linkDebugFrameworkIosSimulatorArm64

# Get available simulators and pick one (iPhone 16 with iOS 18.1)
SIMULATOR_ID="810FCC11-70AB-4CFA-B83C-19CFDA22B2BA"
SIMULATOR_NAME="iPhone 16 Pro"

echo "Booting simulator: $SIMULATOR_NAME..."
xcrun simctl boot "$SIMULATOR_ID" 2>/dev/null || echo "Simulator already booted"

# Open Simulator app
open -a Simulator

# Wait for simulator to fully boot
echo "Waiting for simulator to boot..."
xcrun simctl bootstatus "$SIMULATOR_ID" -b

# Build the Xcode project
echo "Building Xcode project..."
xcodebuild -project apps/iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
  -derivedDataPath build/ios \
  clean build

# Install and launch the app
echo "Installing app on simulator..."
xcrun simctl install "$SIMULATOR_ID" "build/ios/Build/Products/Debug-iphonesimulator/iosApp.app"

echo "Launching app..."
xcrun simctl launch --console "$SIMULATOR_ID" com.jervis.iosApp
