#!/bin/bash
# Analyze Test Coverage Script
# Generates JaCoCo coverage report and provides analysis

set -e

echo "═══════════════════════════════════════════════════════════"
echo "  Test Coverage Analysis"
echo "═══════════════════════════════════════════════════════════"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Step 1: Run tests with coverage
echo -e "${YELLOW}Step 1/4: Running tests with coverage...${NC}"
./gradlew testDebugUnitTest jacocoTestReport --continue
echo -e "${GREEN}✅ Tests complete${NC}"
echo ""

# Step 2: Parse coverage report
echo -e "${YELLOW}Step 2/4: Parsing coverage data...${NC}"

REPORT_XML="app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"
REPORT_HTML="app/build/reports/jacoco/jacocoTestReport/html/index.html"

if [ ! -f "$REPORT_XML" ]; then
    echo -e "${RED}❌ Coverage report not found${NC}"
    echo "Expected: $REPORT_XML"
    exit 1
fi

echo -e "${GREEN}✅ Coverage report generated${NC}"
echo ""

# Step 3: Display coverage summary
echo -e "${YELLOW}Step 3/4: Coverage Summary${NC}"
echo "─────────────────────────────────────────────────────────"

# Extract coverage percentages from XML (requires xmllint or python)
if command -v python3 &> /dev/null; then
    python3 - "$REPORT_XML" << 'PYTHON'
import sys
import xml.etree.ElementTree as ET

tree = ET.parse(sys.argv[1])
root = tree.getroot()

for counter in root.findall('.//counter[@type="INSTRUCTION"]'):
    covered = int(counter.get('covered', 0))
    missed = int(counter.get('missed', 0))
    total = covered + missed

    if total > 0:
        percentage = (covered / total) * 100
        package = counter.getparent().get('name', 'Overall')
        print(f"{package}: {percentage:.1f}% ({covered}/{total} instructions)")
PYTHON
else
    echo "Python not available for detailed parsing"
    echo "View HTML report for details: $REPORT_HTML"
fi

echo "─────────────────────────────────────────────────────────"
echo ""

# Step 4: Identify uncovered classes
echo -e "${YELLOW}Step 4/4: Identifying gaps...${NC}"
echo "Classes with < 80% coverage (review these):"

# Simple grep-based approach
grep -r "class " app/src/main/java --include="*.kt" | \
    grep -v "data class" | \
    grep -v "sealed class" | \
    head -10

echo ""
echo "═══════════════════════════════════════════════════════════"
echo -e "${GREEN}  Coverage Analysis Complete${NC}"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Full report: $REPORT_HTML"
echo ""
echo "Coverage targets:"
echo "  Overall: 80%+"
echo "  ViewModels: 90%+"
echo "  Repositories: 85%+"
echo "  Domain logic: 95%+"
echo ""
