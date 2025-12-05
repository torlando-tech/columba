#!/bin/bash

# Columba Android App - Build and Deploy Script
# Builds the app and deploys it to a connected Android device via ADB

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
APP_MODULE="app"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.lxmf.messenger"
MAIN_ACTIVITY="${PACKAGE_NAME}.MainActivity"

# Functions
print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}➜ $1${NC}"
}

# Parse arguments
CLEAN=false
LAUNCH=false
LOGS=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --clean)
            CLEAN=true
            shift
            ;;
        --launch)
            LAUNCH=true
            shift
            ;;
        --logs)
            LOGS=true
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --clean    Clean build before deploying"
            echo "  --launch   Launch app after installation"
            echo "  --logs     Show logcat after launching (requires --launch)"
            echo "  --help     Show this help message"
            echo ""
            echo "Example:"
            echo "  $0 --clean --launch --logs"
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Main script
print_header "Columba Build & Deploy"

# Step 1: Clean build (optional)
if [ "$CLEAN" = true ]; then
    print_info "Cleaning build..."
    ./gradlew clean
    print_success "Clean complete"
fi

# Step 2: Build the APK
print_info "Building debug APK..."
./gradlew :${APP_MODULE}:assembleDebug

if [ ! -f "$APK_PATH" ]; then
    print_error "APK not found at: $APK_PATH"
    exit 1
fi

print_success "Build complete: $APK_PATH"

# Step 3: Check for connected devices
print_info "Checking for connected devices..."
DEVICES=$(adb devices | grep "device$" | awk '{print $1}')
DEVICE_COUNT=$(echo "$DEVICES" | grep -c . || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    print_error "No Android devices found"
    echo ""
    echo "Please connect your device and ensure:"
    echo "  1. USB debugging is enabled"
    echo "  2. Device is authorized (check phone screen)"
    echo "  3. ADB can detect the device: adb devices"
    exit 1
fi

print_success "Found $DEVICE_COUNT device(s)"
echo ""

# Step 4: Deploy to each device
FAILED_DEVICES=()
for DEVICE_SERIAL in $DEVICES; do
    print_header "Deploying to device: $DEVICE_SERIAL"

    # Install APK
    print_info "Installing APK to $DEVICE_SERIAL..."
    if ! adb -s "$DEVICE_SERIAL" install -r -d "$APK_PATH" 2>&1; then
        echo ""
        print_error "Installation failed on $DEVICE_SERIAL (likely signature mismatch)"
        echo ""
        echo "This usually means the Gradle daemon has stale environment variables."
        echo "Try: ./gradlew --stop && ./deploy.sh"
        echo ""
        echo "If you want to uninstall and lose app data, run:"
        echo "  adb -s $DEVICE_SERIAL uninstall $PACKAGE_NAME && ./deploy.sh"
        FAILED_DEVICES+=("$DEVICE_SERIAL")
        continue
    fi
    print_success "Installation complete on $DEVICE_SERIAL"

    # Launch app (optional)
    if [ "$LAUNCH" = true ]; then
        print_info "Launching app on $DEVICE_SERIAL..."
        adb -s "$DEVICE_SERIAL" shell am start -n "${PACKAGE_NAME}/${MAIN_ACTIVITY}"
        print_success "App launched on $DEVICE_SERIAL"

        # Show logs (optional)
        if [ "$LOGS" = true ]; then
            echo ""
            print_info "Showing logcat for $DEVICE_SERIAL (Ctrl+C to exit)..."
            echo ""
            adb -s "$DEVICE_SERIAL" logcat -c  # Clear logs
            adb -s "$DEVICE_SERIAL" logcat | grep -E "(${PACKAGE_NAME}|BLE|Reticulum|RNS)"
        fi
    fi

    echo ""
done

# Check if any deployments failed
if [ ${#FAILED_DEVICES[@]} -gt 0 ]; then
    echo ""
    print_error "Deployment failed on ${#FAILED_DEVICES[@]} device(s):"
    for FAILED_DEVICE in "${FAILED_DEVICES[@]}"; do
        echo "  • $FAILED_DEVICE"
    done
    exit 1
fi

echo ""
print_header "Deployment Complete! 🚀"
echo ""
print_info "Next steps:"
echo "  • Open the app on your phone"
echo "  • Check BLE settings and permissions"
echo "  • Monitor logs: adb logcat | grep -E 'BLE|Reticulum'"
echo ""
