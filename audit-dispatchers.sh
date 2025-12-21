#!/bin/bash

# audit-dispatchers.sh
# Threading Architecture - Dispatcher Usage Audit Script (CI-Optimized)
#
# This simplified version focuses on critical violations only for fast CI checks.
# For comprehensive analysis, see audit-dispatchers-full.sh

set -euo pipefail

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
VIOLATIONS=0
WARNINGS=0
INFO=0

# Output file
REPORT_FILE="dispatcher-audit-report.txt"
echo "Dispatcher Audit Report - $(date)" > "$REPORT_FILE"
echo "========================================" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

# Helper functions
violation() {
    echo -e "${RED}❌ VIOLATION:${NC} $1"
    echo "❌ VIOLATION: $1" >> "$REPORT_FILE"
    VIOLATIONS=$((VIOLATIONS + 1))
}

warning() {
    echo -e "${YELLOW}⚠️  WARNING:${NC} $1"
    echo "⚠️  WARNING: $1" >> "$REPORT_FILE"
    WARNINGS=$((WARNINGS + 1))
}

info() {
    echo -e "${BLUE}ℹ️  INFO:${NC} $1"
    echo "ℹ️  INFO: $1" >> "$REPORT_FILE"
    INFO=$((INFO + 1))
}

success() {
    echo -e "${GREEN}✅ PASS:${NC} $1"
    echo "✅ PASS: $1" >> "$REPORT_FILE"
}

section() {
    echo ""
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo -e "${GREEN}$1${NC}"
    echo -e "${GREEN}═══════════════════════════════════════${NC}"
    echo "" >> "$REPORT_FILE"
    echo "═══════════════════════════════════════" >> "$REPORT_FILE"
    echo "$1" >> "$REPORT_FILE"
    echo "═══════════════════════════════════════" >> "$REPORT_FILE"
}

# Find source directories
APP_SRC="app/src/main/java"
RETICULUM_SRC="reticulum/src/main/java"
DATA_SRC="data/src/main/java"

section "1. Checking for runBlocking in Production Code"

# Check for runBlocking (should be 0 in production code after Phase 1)
# Allow exceptions marked with "// THREADING: allowed" inline comment
# Ignore import statements and pure comment lines
RUNBLOCKING_MATCHES=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -n "runBlocking" 2>/dev/null | \
    grep -v "^[^:]*:.*import " | \
    grep -v "^[^:]*:[0-9]*:[[:space:]]*//" | \
    grep -v "THREADING: allowed" || true)

if [ -z "$RUNBLOCKING_MATCHES" ]; then
    success "No runBlocking found in production code (or all instances are allowed)"
else
    while IFS= read -r line; do
        violation "runBlocking found: $line"
    done <<< "$RUNBLOCKING_MATCHES"
fi

section "2. Checking for Forbidden Patterns"

# Check for GlobalScope (should never be used)
GLOBALSCOPE_MATCHES=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -n "GlobalScope" 2>/dev/null | \
    grep -v "//" || true)

if [ -z "$GLOBALSCOPE_MATCHES" ]; then
    success "No GlobalScope usage found"
else
    while IFS= read -r line; do
        violation "GlobalScope found (use structured concurrency): $line"
    done <<< "$GLOBALSCOPE_MATCHES"
fi

# Check for Dispatchers.Unconfined (should never be used)
UNCONFINED_MATCHES=$(find $APP_SRC $RETICULUM_SRC $DATA_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -n "Dispatchers\.Unconfined" 2>/dev/null | \
    grep -v "//" || true)

if [ -z "$UNCONFINED_MATCHES" ]; then
    success "No Dispatchers.Unconfined usage found"
else
    while IFS= read -r line; do
        violation "Dispatchers.Unconfined found (never use): $line"
    done <<< "$UNCONFINED_MATCHES"
fi

section "3. Checking Python Initialization Uses Main.immediate"

# Find Python wrapper.callAttr("initialize") calls
PYTHON_INIT_MATCHES=$(find $APP_SRC -name "*.kt" 2>/dev/null | \
    grep -v -E "(test|Test|build)" | \
    xargs grep -B 5 'callAttr.*"initialize"' 2>/dev/null | \
    grep -E "(Dispatchers\.(Main|IO|Default)|withContext)" || true)

if echo "$PYTHON_INIT_MATCHES" | grep -q "Dispatchers\.Main\.immediate"; then
    success "Python initialization uses Dispatchers.Main.immediate"
elif [ -z "$PYTHON_INIT_MATCHES" ]; then
    info "Could not verify Python initialization dispatcher (no matches found)"
else
    violation "Python initialization may not be using Dispatchers.Main.immediate"
fi

section "4. Summary - CI Optimized Check Complete"

info "Sections 5-9 skipped for CI performance (non-critical checks)"
info "All critical threading violations checked (runBlocking, GlobalScope, Unconfined, Python init)"
info "For comprehensive analysis, run audit-dispatchers-full.sh locally"

echo ""
echo "═══════════════════════════════════════"
echo "AUDIT SUMMARY"
echo "═══════════════════════════════════════"
echo "❌ Violations: $VIOLATIONS"
echo "⚠️  Warnings:   $WARNINGS"
echo "ℹ️  Info:       $INFO"
echo ""

if [ $VIOLATIONS -eq 0 ]; then
    echo -e "${GREEN}✅ No critical violations found!${NC}"
else
    echo -e "${RED}❌ $VIOLATIONS critical violations require fixing${NC}"
fi

if [ $WARNINGS -gt 0 ]; then
    echo -e "${YELLOW}⚠️  $WARNINGS warnings should be reviewed${NC}"
fi

echo ""
echo "Full report saved to: $REPORT_FILE"
echo ""

# Exit with error if violations found
exit $VIOLATIONS
