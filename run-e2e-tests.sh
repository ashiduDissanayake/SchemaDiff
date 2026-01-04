#!/bin/bash
# E2E Test Runner Script
# Usage: ./run-e2e-tests.sh [options]
#
# Options:
#   --mysql      Run MySQL tests only
#   --postgres   Run PostgreSQL tests only
#   --mssql      Run MSSQL tests only
#   --oracle     Run Oracle tests only
#   --fast       Run fast tests only (default)
#   --full       Run full schema tests
#   --all        Run all tests
#   --update     Update expected snapshots
#   --help       Show this help

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
TEST_GROUPS=""
EXCLUDED_GROUPS=""
UPDATE_SNAPSHOTS=""
MVN_CMD="mvn"

# Check for mvn21 alias
if command -v mvn21 &> /dev/null; then
    MVN_CMD="mvn21"
fi

show_help() {
    echo "E2E Test Runner for SchemaDiff"
    echo ""
    echo "Usage: $0 [options]"
    echo ""
    echo "Database Options:"
    echo "  --mysql      Run MySQL tests only"
    echo "  --postgres   Run PostgreSQL tests only"
    echo "  --mssql      Run MSSQL tests only"
    echo "  --oracle     Run Oracle tests only"
    echo ""
    echo "Scope Options:"
    echo "  --fast       Run fast tests only (uses minimal schema)"
    echo "  --full       Run full schema tests"
    echo "  --all        Run all tests"
    echo ""
    echo "Test Type Options:"
    echo "  --tables     Run only table-related tests"
    echo "  --columns    Run only column-related tests"
    echo "  --constraints Run only constraint-related tests"
    echo "  --indexes    Run only index-related tests"
    echo ""
    echo "Other Options:"
    echo "  --update     Update expected output snapshots"
    echo "  --help       Show this help"
    echo ""
    echo "Examples:"
    echo "  $0 --mysql --fast        # Run fast MySQL tests"
    echo "  $0 --postgres --full     # Run full PostgreSQL tests"
    echo "  $0 --all                 # Run all tests"
    echo "  $0 --mysql --tables      # Run MySQL table tests"
    echo "  $0 --update --mysql      # Update MySQL snapshots"
}

# Parse arguments
DB_FILTER=""
SPEED_FILTER="fast"
TYPE_FILTER=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --mysql)
            DB_FILTER="mysql"
            shift
            ;;
        --postgres)
            DB_FILTER="postgres"
            shift
            ;;
        --mssql)
            DB_FILTER="mssql"
            shift
            ;;
        --oracle)
            DB_FILTER="oracle"
            shift
            ;;
        --fast)
            SPEED_FILTER="fast"
            shift
            ;;
        --full)
            SPEED_FILTER="full-schema"
            shift
            ;;
        --all)
            SPEED_FILTER=""
            shift
            ;;
        --tables)
            TYPE_FILTER="tables"
            shift
            ;;
        --columns)
            TYPE_FILTER="columns"
            shift
            ;;
        --constraints)
            TYPE_FILTER="constraints"
            shift
            ;;
        --indexes)
            TYPE_FILTER="indexes"
            shift
            ;;
        --update)
            UPDATE_SNAPSHOTS="-DupdateSnapshots=true"
            shift
            ;;
        --help)
            show_help
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            show_help
            exit 1
            ;;
    esac
done

# Build test groups filter
GROUPS_PARTS=()
if [[ -n "$DB_FILTER" ]]; then
    GROUPS_PARTS+=("$DB_FILTER")
fi
if [[ -n "$SPEED_FILTER" ]]; then
    GROUPS_PARTS+=("$SPEED_FILTER")
fi
if [[ -n "$TYPE_FILTER" ]]; then
    GROUPS_PARTS+=("$TYPE_FILTER")
fi

if [[ ${#GROUPS_PARTS[@]} -gt 0 ]]; then
    TEST_GROUPS=$(IFS=' & '; echo "${GROUPS_PARTS[*]}")
fi

# Build Maven command
MVN_ARGS=("test" "-B")

if [[ -n "$TEST_GROUPS" ]]; then
    MVN_ARGS+=("-Dtest.groups=$TEST_GROUPS")
fi

if [[ -n "$UPDATE_SNAPSHOTS" ]]; then
    MVN_ARGS+=("$UPDATE_SNAPSHOTS")
fi

# Display what we're running
echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║             SchemaDiff E2E Test Runner                     ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${YELLOW}Configuration:${NC}"
echo "  Database:  ${DB_FILTER:-all}"
echo "  Speed:     ${SPEED_FILTER:-all}"
echo "  Type:      ${TYPE_FILTER:-all}"
echo "  Snapshots: ${UPDATE_SNAPSHOTS:-verify}"
echo ""
echo -e "${YELLOW}Running:${NC} $MVN_CMD ${MVN_ARGS[*]}"
echo ""

# Run tests
$MVN_CMD "${MVN_ARGS[@]}"

# Show summary
if [[ $? -eq 0 ]]; then
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║                    ALL TESTS PASSED ✓                      ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
else
    echo ""
    echo -e "${RED}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║                    SOME TESTS FAILED ✗                     ║${NC}"
    echo -e "${RED}╚════════════════════════════════════════════════════════════╝${NC}"
    exit 1
fi

