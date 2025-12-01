#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
APP_PACKAGE="com.lxmf.messenger"
MAIN_ACTIVITY="${APP_PACKAGE}.MainActivity"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

# Custom AVD name can be passed as first argument
CUSTOM_AVD="$1"

echo -e "${BLUE}=== Columba LXMF Messenger - Build and Launch ===${NC}\n"

# Step 1: Build the APK
echo -e "${YELLOW}[1/5] Building APK...${NC}"
./gradlew assembleDebug
if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Build failed!${NC}"
    exit 1
fi

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}Error: APK not found at $APK_PATH${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Build successful: $APK_PATH${NC}\n"

# Step 2: Check if adb is available
echo -e "${YELLOW}[2/5] Checking ADB...${NC}"
if ! command -v adb &> /dev/null; then
    echo -e "${RED}Error: adb not found. Please install Android SDK Platform-Tools.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ ADB is available${NC}\n"

# Step 3: Check for running emulator/device
echo -e "${YELLOW}[3/5] Checking for running emulator...${NC}"
adb devices -l | grep -v "List of devices" | grep -v "^$" | grep -E "device|emulator" > /dev/null
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Emulator/device is already running${NC}\n"
else
    echo -e "${YELLOW}No emulator running. Starting one...${NC}"

    # Check if emulator command is available
    if ! command -v emulator &> /dev/null; then
        echo -e "${RED}Error: 'emulator' command not found.${NC}"
        echo -e "${RED}Please add Android SDK emulator to your PATH or start an emulator manually.${NC}"
        exit 1
    fi

    # Get available AVDs
    if [ -n "$CUSTOM_AVD" ]; then
        AVD_NAME="$CUSTOM_AVD"
        echo -e "${BLUE}Using specified AVD: $AVD_NAME${NC}"
    else
        # List available AVDs
        AVDS=$(emulator -list-avds)
        if [ -z "$AVDS" ]; then
            echo -e "${RED}Error: No AVDs found. Please create an Android Virtual Device first.${NC}"
            echo -e "${YELLOW}You can create one using: Android Studio > Tools > Device Manager${NC}"
            exit 1
        fi

        # Use the first AVD
        AVD_NAME=$(echo "$AVDS" | head -n 1)
        echo -e "${BLUE}Available AVDs:${NC}"
        echo "$AVDS" | sed 's/^/  - /'
        echo -e "${BLUE}Using: $AVD_NAME${NC}"
    fi

    # Start emulator in background
    echo -e "${YELLOW}Starting emulator '$AVD_NAME'...${NC}"
    QT_QPA_PLATFORM=xcb emulator -avd "$AVD_NAME" -no-snapshot-load > /dev/null 2>&1 &
    EMULATOR_PID=$!

    # Wait for emulator to boot
    echo -e "${YELLOW}Waiting for emulator to boot...${NC}"
    adb wait-for-device

    # Wait for boot to complete
    BOOT_COMPLETE=0
    TIMEOUT=120  # 2 minutes timeout
    ELAPSED=0
    while [ $BOOT_COMPLETE -eq 0 ] && [ $ELAPSED -lt $TIMEOUT ]; do
        BOOT_STATUS=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        if [ "$BOOT_STATUS" = "1" ]; then
            BOOT_COMPLETE=1
        else
            echo -n "."
            sleep 2
            ELAPSED=$((ELAPSED + 2))
        fi
    done
    echo ""

    if [ $BOOT_COMPLETE -eq 0 ]; then
        echo -e "${RED}Error: Emulator boot timeout!${NC}"
        exit 1
    fi

    # Give it a few more seconds to fully stabilize
    echo -e "${YELLOW}Waiting for launcher...${NC}"
    sleep 5

    echo -e "${GREEN}✓ Emulator is ready${NC}\n"
fi

# Step 4: Install APK
echo -e "${YELLOW}[4/5] Installing APK...${NC}"
adb install -r "$APK_PATH"
if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Installation failed!${NC}"
    exit 1
fi
echo -e "${GREEN}✓ APK installed successfully${NC}\n"

# Step 5: Launch app
echo -e "${YELLOW}[5/5] Launching app...${NC}"
adb shell am start -n "${APP_PACKAGE}/${MAIN_ACTIVITY}"
if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Failed to launch app!${NC}"
    exit 1
fi
echo -e "${GREEN}✓ App launched successfully${NC}\n"

echo -e "${GREEN}=== Complete! ===${NC}"
echo -e "${BLUE}App package: ${APP_PACKAGE}${NC}"
echo -e "${BLUE}To view logs: adb logcat | grep ${APP_PACKAGE}${NC}"
echo -e "${BLUE}To uninstall: adb uninstall ${APP_PACKAGE}${NC}"
