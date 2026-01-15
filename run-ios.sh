#!/bin/bash

# Check if simulator mode is requested
if [ "$1" = "simulator" ]; then
    USE_SIMULATOR=true
    DEVICE_NAME="iPhone 16 Pro"
    DEVICE_ID="810FCC11-70AB-4CFA-B83C-19CFDA22B2BA"
    echo "Running on simulator: $DEVICE_NAME"
else
    USE_SIMULATOR=false
    DEVICE_NAME="Jan - iPhone 14"
    echo "Running on physical device: $DEVICE_NAME"
fi

# Build the iOS framework
echo "Building iOS framework..."
if [ "$USE_SIMULATOR" = true ]; then
    ./gradlew :apps:mobile:linkDebugFrameworkIosSimulatorArm64
else
    ./gradlew :apps:mobile:linkDebugFrameworkIosArm64
fi

if [ "$USE_SIMULATOR" = true ]; then
    # Simulator mode
    echo "Booting simulator: $DEVICE_NAME..."
    xcrun simctl boot "$DEVICE_ID" 2>/dev/null || echo "Simulator already booted"

    # Open Simulator app
    open -a Simulator

    # Wait for simulator to fully boot
    echo "Waiting for simulator to boot..."
    xcrun simctl bootstatus "$DEVICE_ID" -b
    echo "Monitoring boot status for $DEVICE_NAME ($DEVICE_ID)."
    while [ "$(xcrun simctl list devices | grep "$DEVICE_ID" | grep -c "Booted")" -eq 0 ]; do
        echo "Waiting for device to boot..."
        sleep 1
    done
    echo "Device already booted, nothing to do."

    # Build the Xcode project for simulator
    echo "Building Xcode project..."
    echo "Command line invocation:"
    echo "    xcodebuild -project apps/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination \"platform=iOS Simulator,id=$DEVICE_ID\" -derivedDataPath build/ios clean build"
    xcodebuild -project apps/iosApp/iosApp.xcodeproj \
      -scheme iosApp \
      -configuration Debug \
      -destination "platform=iOS Simulator,id=$DEVICE_ID" \
      -derivedDataPath build/ios \
      clean build

    # Install and launch the app
    echo "Installing app on simulator..."
    xcrun simctl install "$DEVICE_ID" "build/ios/Build/Products/Debug-iphonesimulator/iosApp.app"

    echo "Launching app..."
    xcrun simctl launch --console "$DEVICE_ID" com.jervis.iosApp
else
    # Physical device mode
    echo "Building Xcode project..."
    echo "Command line invocation:"
    echo "    xcodebuild -project apps/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination \"platform=iOS,name=$DEVICE_NAME\" -derivedDataPath build/ios clean build"
    xcodebuild -project apps/iosApp/iosApp.xcodeproj \
      -scheme iosApp \
      -configuration Debug \
      -destination "platform=iOS,name=$DEVICE_NAME" \
      -derivedDataPath build/ios \
      clean build

    echo "Installing and launching app on physical device..."
    # Find device ID for the given name
    DEVICE_ID=$(xcrun devicectl list devices --verbose | grep -B 20 "$DEVICE_NAME" | grep "udid:" | head -n 1 | sed 's/.*("//' | sed 's/")//')
    
    if [ -z "$DEVICE_ID" ]; then
        DEVICE_ID=$(xcrun devicectl list devices | grep "$DEVICE_NAME" | awk '{print $3}')
    fi
    
    if [ -z "$DEVICE_ID" ] || [ ${#DEVICE_ID} -lt 20 ]; then
        # Fallback for newer Xcode/devicectl output formats
        DEVICE_ID=$(xcrun devicectl list devices | grep -B 1 "$DEVICE_NAME" | grep "Identifier" | awk '{print $NF}' | head -n 1)
    fi

    if [ -n "$DEVICE_ID" ]; then
        echo "Found Device ID: $DEVICE_ID"
        echo "Installing app..."
        xcrun devicectl device install app --device "$DEVICE_ID" "build/ios/Build/Products/Debug-iphoneos/iosApp.app"
        
        echo "Launching app..."
        xcrun devicectl device process launch --device "$DEVICE_ID" com.jervis.iosApp
    else
        echo "Error: Could not find device ID for '$DEVICE_NAME'. Please ensure it's connected and paired."
        echo "You can check connected devices with: xcrun devicectl list devices"
    fi
fi
