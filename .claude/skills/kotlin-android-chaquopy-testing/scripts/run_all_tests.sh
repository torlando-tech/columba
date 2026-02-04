#!/bin/bash
# Run All Tests Script
# Executes complete test suite (unit + instrumented) with proper configuration

set -e

echo "═══════════════════════════════════════════════════════════"
echo "  Running Complete Test Suite"
echo "═══════════════════════════════════════════════════════════"
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Run unit tests
echo -e "${YELLOW}Step 1/3: Running unit tests...${NC}"
./gradlew test --continue || {
    echo -e "${RED}❌ Unit tests failed${NC}"
    exit 1
}
echo -e "${GREEN}✅ Unit tests passed${NC}"
echo ""

# Step 2: Check if device/emulator is connected
echo -e "${YELLOW}Step 2/3: Checking for connected device...${NC}"
DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device" | wc -l)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo -e "${RED}❌ No device/emulator connected${NC}"
    echo "Please start an emulator or connect a device to run instrumented tests"
    echo "Skipping instrumented tests..."
    SKIP_INSTRUMENTED=true
else
    echo -e "${GREEN}✅ Found ${DEVICE_COUNT} connected device(s)${NC}"
    SKIP_INSTRUMENTED=false
fi
echo ""

# Step 3: Run instrumented tests (if device available)
if [ "$SKIP_INSTRUMENTED" = false ]; then
    echo -e "${YELLOW}Step 3/3: Running instrumented tests...${NC}"
    ./gradlew connectedAndroidTest --continue || {
        echo -e "${RED}❌ Instrumented tests failed${NC}"
        exit 1
    }
    echo -e "${GREEN}✅ Instrumented tests passed${NC}"
else
    echo -e "${YELLOW}Step 3/3: Skipped (no device)${NC}"
fi
echo ""

# Summary
echo "═══════════════════════════════════════════════════════════"
echo -e "${GREEN}  All Tests Complete${NC}"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Test reports available at:"
echo "  Unit tests: app/build/reports/tests/testDebugUnitTest/index.html"
if [ "$SKIP_INSTRUMENTED" = false ]; then
    echo "  Instrumented tests: app/build/reports/androidTests/connected/index.html"
fi
echo ""
