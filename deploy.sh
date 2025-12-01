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
DEVICE_COUNT=$(adb devices | grep -v "List of devices" | grep -c "device$" || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    print_error "No Android devices found"
    echo ""
    echo "Please connect your device and ensure:"
    echo "  1. USB debugging is enabled"
    echo "  2. Device is authorized (check phone screen)"
    echo "  3. ADB can detect the device: adb devices"
    exit 1
elif [ "$DEVICE_COUNT" -gt 1 ]; then
    print_info "Multiple devices connected:"
    adb devices
    print_error "Please connect only one device or specify target with ADB_SERIAL environment variable"
    exit 1
fi

DEVICE_INFO=$(adb devices | grep "device$" | head -n 1)
print_success "Device connected: $DEVICE_INFO"

# Step 4: Install APK
print_info "Installing APK to device..."
if ! adb install -r -d "$APK_PATH" 2>&1; then
    print_info "Installation failed, attempting to uninstall and reinstall..."
    adb uninstall "$PACKAGE_NAME" 2>/dev/null || true
    adb install "$APK_PATH"
fi
print_success "Installation complete"

# Step 5: Launch app (optional)
if [ "$LAUNCH" = true ]; then
    print_info "Launching app..."
    adb shell am start -n "${PACKAGE_NAME}/${MAIN_ACTIVITY}"
    print_success "App launched"

    # Step 6: Show logs (optional)
    if [ "$LOGS" = true ]; then
        echo ""
        print_info "Showing logcat (Ctrl+C to exit)..."
        echo ""
        adb logcat -c  # Clear logs
        adb logcat | grep -E "(${PACKAGE_NAME}|BLE|Reticulum|RNS)"
    fi
fi

echo ""
print_header "Deployment Complete! 🚀"
echo ""
print_info "Next steps:"
echo "  • Open the app on your phone"
echo "  • Check BLE settings and permissions"
echo "  • Monitor logs: adb logcat | grep -E 'BLE|Reticulum'"
echo ""
