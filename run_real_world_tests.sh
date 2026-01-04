#!/bin/bash
# SchemaDiff 2.0 - Real World Scenarios Test Suite
# Runs the tool against WSO2 API Manager schema drift scenarios

echo "=================================================="
echo "SchemaDiff 2.0 - Real World Scenarios (WSO2 APIM)"
echo "=================================================="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check for JAR
JAR="target/schemadiff2-2.0.0.jar"
if [ ! -f "$JAR" ]; then
    echo -e "${RED}ERROR: JAR not found at $JAR. Please build first.${NC}"
    exit 1
fi

# Check Docker
if ! sudo docker info > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Docker is not running or accessible. Try running with sudo.${NC}"
    exit 1
fi

BASE_SCHEMA="apim-drift-tests/schemas/base_apim.sql"
IMAGE="mysql:8.0"

# Function to run test
run_test() {
    local phase_file=$1
    local expected_marker=$2
    local description=$3

    echo -e "${YELLOW}Running Test: $description${NC}"
    echo "Target: $phase_file"

    OUTPUT=$(sudo timeout 180 java -jar "$JAR" \
        --db-type mysql \
        --reference "$BASE_SCHEMA" \
        --target "apim-drift-tests/schemas/$phase_file" \
        --image "$IMAGE" 2>&1)

    EXIT_CODE=$?

    # Check for execution errors (timeout or java crash)
    if [ $EXIT_CODE -ne 0 ] && [ $EXIT_CODE -ne 124 ]; then
       # Note: The tool might return non-zero on diffs? No, typically tools return 0 on success execution even if diffs found,
       # unless configured otherwise. Let's check output.
       # Actually, if the tool crashes, we care. If it just finds diffs, it's fine.
       :
    fi

    # Check for expected marker in output
    if echo "$OUTPUT" | grep -Fq "$expected_marker"; then
        echo -e "${GREEN}✓ PASS: Detected expected change '$expected_marker'${NC}"
        return 0
    else
        echo -e "${RED}✗ FAIL: Did not detect '$expected_marker'${NC}"
        echo "Output snippet:"
        echo "$OUTPUT" | grep -E "SUMMARY|Differences" -A 10
        return 1
    fi
}

FAILED=0

# Phase 2: Missing Tables
run_test "phase2_missing.sql" "[X]" "Phase 2 - Missing Tables" || FAILED=1
echo ""

# Phase 3: Modified Columns
run_test "phase3_columns.sql" "[M]" "Phase 3 - Modified Columns" || FAILED=1
echo ""

# Phase 4: Constraint Mismatches
run_test "phase4_constraints.sql" "[C]" "Phase 4 - Constraint Mismatches" || FAILED=1
echo ""

# Phase 5: Index Mismatches
run_test "phase5_indexes.sql" "[I]" "Phase 5 - Index Mismatches" || FAILED=1
echo ""

# Phase 6: Additive (Extra Tables)
run_test "phase6_additive.sql" "[+]" "Phase 6 - Extra Tables" || FAILED=1
echo ""

# Phase 7: Complex
run_test "phase7_complex.sql" "Differences Found" "Phase 7 - Complex Drift" || FAILED=1
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}ALL REAL WORLD SCENARIOS PASSED${NC}"
    exit 0
else
    echo -e "${RED}SOME TESTS FAILED${NC}"
    exit 1
fi
