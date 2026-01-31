#!/bin/bash
#
# Device Verification Script for Columba
#
# This script builds the debug APK, installs it on a connected Android device,
# runs smoke tests, and captures logs/screenshots on failure.
#
# Usage:
#   ./scripts/verify-on-device.sh              # Auto-detect device
#   ./scripts/verify-on-device.sh 10.0.0.71    # Specify device IP
#   ./scripts/verify-on-device.sh --test-only  # Skip build, run tests only
#
# Requirements:
#   - ADB installed and in PATH
#   - Device connected via USB or adb connect
#   - For network devices: 10.0.0.x range (phones at .71 and .249)
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging helpers
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Script directory (allows running from any location)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Output directory for artifacts
OUTPUT_DIR="/tmp/columba-verify"
mkdir -p "$OUTPUT_DIR"

# Parse arguments
TEST_ONLY=false
DEVICE_IP=""

for arg in "$@"; do
    case $arg in
        --test-only)
            TEST_ONLY=true
            shift
            ;;
        *)
            DEVICE_IP="$arg"
            shift
            ;;
    esac
done

# Navigate to project root
cd "$PROJECT_ROOT"

# Find connected device
find_device() {
    log_info "Looking for connected Android devices..."

    # First, refresh ADB server
    adb kill-server 2>/dev/null || true
    adb start-server

    # If device IP specified, try to connect
    if [[ -n "$DEVICE_IP" ]]; then
        log_info "Connecting to specified device: $DEVICE_IP"
        # Try common ADB ports
        for port in 5555 5556 5557; do
            if adb connect "${DEVICE_IP}:${port}" 2>/dev/null | grep -q "connected"; then
                DEVICE_SERIAL="${DEVICE_IP}:${port}"
                log_success "Connected to $DEVICE_SERIAL"
                return 0
            fi
        done
        log_error "Could not connect to $DEVICE_IP"
        return 1
    fi

    # Auto-detect from adb devices
    DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | awk '{print $1}')
    DEVICE_COUNT=$(echo "$DEVICES" | grep -c . || echo "0")

    if [[ "$DEVICE_COUNT" -eq 0 ]]; then
        log_warn "No devices found. Trying known IPs in 10.0.0.x range..."

        # Try known phone IPs
        for ip in "10.0.0.71" "10.0.0.249"; do
            for port in 5555 5556 5557; do
                if timeout 2 adb connect "${ip}:${port}" 2>/dev/null | grep -q "connected"; then
                    DEVICE_SERIAL="${ip}:${port}"
                    log_success "Connected to $DEVICE_SERIAL"
                    return 0
                fi
            done
        done

        log_error "No devices found. Connect a device via USB or run: adb connect <ip>:5555"
        return 1
    elif [[ "$DEVICE_COUNT" -eq 1 ]]; then
        DEVICE_SERIAL=$(echo "$DEVICES" | head -1)
        log_success "Found device: $DEVICE_SERIAL"
    else
        log_info "Multiple devices found:"
        echo "$DEVICES" | nl
        DEVICE_SERIAL=$(echo "$DEVICES" | head -1)
        log_info "Using first device: $DEVICE_SERIAL (specify IP to override)"
    fi
}

# Build the APK
build_apk() {
    if [[ "$TEST_ONLY" == "true" ]]; then
        log_info "Skipping build (--test-only)"
        return 0
    fi

    log_info "Building noSentry debug APK..."

    # Set JAVA_HOME for Android builds
    export JAVA_HOME=/home/tyler/android-studio/jbr

    if ./gradlew assembleNoSentryDebug --quiet; then
        APK_PATH="app/build/outputs/apk/noSentry/debug/app-noSentry-universal-debug.apk"

        if [[ ! -f "$APK_PATH" ]]; then
            # Try to find any APK in the output directory
            APK_PATH=$(find app/build/outputs/apk/noSentry/debug -name "*.apk" | head -1)
        fi

        if [[ -f "$APK_PATH" ]]; then
            log_success "APK built: $APK_PATH"
        else
            log_error "APK not found after build"
            return 1
        fi
    else
        log_error "Build failed"
        return 1
    fi
}

# Install APK on device
install_apk() {
    if [[ "$TEST_ONLY" == "true" ]]; then
        log_info "Skipping install (--test-only)"
        return 0
    fi

    log_info "Installing APK on $DEVICE_SERIAL..."

    if adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"; then
        log_success "APK installed successfully"
    else
        log_warn "Install failed, trying with -t flag (test APK)..."
        if adb -s "$DEVICE_SERIAL" install -r -t "$APK_PATH"; then
            log_success "APK installed with -t flag"
        else
            log_error "Failed to install APK"
            return 1
        fi
    fi
}

# Run instrumented smoke tests
run_smoke_tests() {
    log_info "Running smoke tests on $DEVICE_SERIAL..."

    export JAVA_HOME=/home/tyler/android-studio/jbr

    # Build test APK first
    log_info "Building test APK..."
    if ! ./gradlew :app:assembleNoSentryDebugAndroidTest --quiet; then
        log_error "Failed to build test APK"
        return 1
    fi

    # Run specific smoke test if it exists, otherwise run all androidTests
    TEST_CLASS="com.lxmf.messenger.smoke.SmokeTest"

    log_info "Running instrumented tests..."
    if ./gradlew :app:connectedNoSentryDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
        --info 2>&1 | tee "$OUTPUT_DIR/test-output.log"; then
        log_success "Smoke tests passed!"
        return 0
    else
        log_error "Smoke tests failed"
        return 1
    fi
}

# Capture device state for debugging
capture_debug_info() {
    log_info "Capturing debug information..."

    local timestamp=$(date +%Y%m%d_%H%M%S)

    # Screenshot
    log_info "Taking screenshot..."
    adb -s "$DEVICE_SERIAL" exec-out screencap -p > "$OUTPUT_DIR/screen_${timestamp}.png" 2>/dev/null || true

    # Logcat (last 1000 lines, filtered for our app)
    log_info "Capturing logcat..."
    adb -s "$DEVICE_SERIAL" logcat -d -t 1000 \
        --pid=$(adb -s "$DEVICE_SERIAL" shell pidof com.lxmf.messenger 2>/dev/null || echo "0") \
        > "$OUTPUT_DIR/logcat_${timestamp}.log" 2>/dev/null || true

    # Full logcat for comprehensive debugging
    adb -s "$DEVICE_SERIAL" logcat -d -t 2000 > "$OUTPUT_DIR/logcat_full_${timestamp}.log" 2>/dev/null || true

    # Device info
    echo "=== Device Info ===" > "$OUTPUT_DIR/device_info_${timestamp}.txt"
    adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk >> "$OUTPUT_DIR/device_info_${timestamp}.txt" 2>/dev/null || true
    adb -s "$DEVICE_SERIAL" shell getprop ro.product.model >> "$OUTPUT_DIR/device_info_${timestamp}.txt" 2>/dev/null || true

    log_success "Debug info saved to $OUTPUT_DIR/"
    ls -la "$OUTPUT_DIR/"
}

# Main execution
main() {
    log_info "=== Columba Device Verification ==="
    log_info "Output directory: $OUTPUT_DIR"

    # Step 1: Find device
    find_device || exit 1

    # Step 2: Build APK
    build_apk || exit 1

    # Step 3: Install APK
    install_apk || exit 1

    # Step 4: Run tests
    if run_smoke_tests; then
        log_success "=== Verification Complete ==="
        capture_debug_info
        exit 0
    else
        log_error "=== Verification Failed ==="
        capture_debug_info
        exit 1
    fi
}

# Run main
main
