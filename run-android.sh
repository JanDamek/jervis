#!/bin/bash
set -e

MOBILE_MODULE=":apps:mobile"
APK_PATH="apps/mobile/build/outputs/apk/debug/mobile-debug.apk"
PACKAGE="com.jervis.mobile"
ACTIVITY="com.jervis.mobile.MainActivity"

echo "=== Building and deploying Jervis Android ==="

# Step 1: Build debug APK
echo "Step 1/3: Building debug APK..."
./gradlew ${MOBILE_MODULE}:assembleDebug

if [ ! -f "$APK_PATH" ]; then
    # Try alternative path
    APK_PATH=$(find apps/mobile/build/outputs/apk -name "*.apk" -type f | head -1)
    if [ -z "$APK_PATH" ]; then
        echo "Error: APK not found after build"
        exit 1
    fi
fi
echo "✓ APK built: $APK_PATH"

# Step 2: Get connected devices
echo "Step 2/3: Finding devices..."
DEVICES=$(adb devices -l | grep "device " | grep -v "^List" | awk '{print $1}')
DEVICE_COUNT=$(echo "$DEVICES" | grep -c .)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "Error: No Android devices connected"
    exit 1
fi

echo "Found $DEVICE_COUNT device(s)"

# Step 3: Install and launch on each device
echo "Step 3/3: Installing and launching..."
for DEVICE in $DEVICES; do
    MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    echo ""
    echo "--- $MODEL ($DEVICE) ---"

    echo "  Installing APK..."
    adb -s "$DEVICE" install -r "$APK_PATH"

    echo "  Launching app..."
    adb -s "$DEVICE" shell am start -n "$PACKAGE/$ACTIVITY" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER

    echo "  ✓ $MODEL done"
done

echo ""
echo "=== ✓ Jervis deployed to $DEVICE_COUNT device(s) ==="
