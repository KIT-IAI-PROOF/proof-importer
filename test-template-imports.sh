#!/bin/bash

###############################################################################
# Template Import Verification Test Script
# 
# Tests all three ways to import templates into PROOF:
# 1. Manual startup import script (startup-import.sh)
# 2. Automatic docker compose with import-templates profile
# 3. Manual CLI import via startImporter
#
# Verifies successful import by querying the database
###############################################################################

# Don't use set -e as it can interfere with signal handling and error recovery
set +e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test volume and container naming
TEST_POSTGRES_CONTAINER_NAME="TEST_postgres-proof-importer-unittest"
TEST_POSTGRES_VOLUME_NAME="TEST_postgres-importer-service-unittest"
TEST_FILES_VOLUME_NAME="TEST_postgres-proof-files-unittest"

construct_abs_path() {
    abs_path=""
    # Create absolute path
    if [ -d "$1" ]; then
        abs_path="$(cd "$1" && pwd)"
    fi
    echo "$abs_path"
}

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Configuration - use absolute paths
PROOF_ENVIRONMENT_PATH="${PROOF_ENVIRONMENT_PATH:-$SCRIPT_DIR/../proof-environment}"
PROOF_SCRIPTS_PATH="${PROOF_DEVELOPMENT_PATH:-$SCRIPT_DIR/../proof-developing/scripts}"
# Convert to absolute path if relative
PROOF_ENVIRONMENT_PATH=$(construct_abs_path $PROOF_ENVIRONMENT_PATH)
if [[ "$PROOF_ENVIRONMENT_PATH" = "" ]]; then
    # Path doesn't exist yet, construct absolute path from SCRIPT_DIR
    PROOF_ENVIRONMENT_PATH="$(cd "$SCRIPT_DIR" && cd ../proof-environment 2>/dev/null && pwd)"
fi
PROOF_SCRIPTS_PATH=$(construct_abs_path $PROOF_SCRIPTS_PATH)

TEMPLATES_DIR="$PROOF_ENVIRONMENT_PATH/data/templates"
DOCKER_COMPOSE_DIR="$PROOF_ENVIRONMENT_PATH/docker"
DOCKER_COMPOSE_FILE="docker-compose.dev.yaml"
TEST_DOCKER_COMPOSE_FILE="docker-compose.test-importer.yaml"
DB_HOST="${DB_HOST:-localhost}"
DB_USER="${DB_USER:-admin}"
DB_PASSWORD="${DB_PASSWORD:-admin}"
DB_NAME="proof"
PROOF_ENV_FILE="$DOCKER_COMPOSE_DIR/proof.env"
# Control variables
GENERATE_COMPOSE_FILE=-1
LOG_ARGUMENT=""

# Test results tracking
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Test selection (which tests to run)
RUN_SCRIPT_TEST=0
RUN_AUTO_TEST=0
RUN_CLI_TEST=0
RUN_ALL_TESTS=0

###############################################################################
# Utility Functions
###############################################################################

log_info() {
    echo -e "${BLUE}[TEST]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASSED_TESTS=$((PASSED_TESTS + 1))
}

log_failure() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAILED_TESTS=$((FAILED_TESTS + 1))
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_section() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

###############################################################################
# Argument Parsing Functions
###############################################################################

print_usage() {
    cat << 'EOF'
Usage: test-template-imports.sh [OPTIONS]

Options:
  --envDir-path <path>    Path to proof-environment directory (default: ../proof-environment)
  --all                   Run all tests (default if no specific test is selected)
  -s, --script            Run bootstrap script test only
  (-a, --auto)            [Not supported yet] Run automatic docker compose test only
  (-c, --cli)             [Not supported yet] Run CLI import test only
  -h, --help              Show this help message

Examples:
  ./test-template-imports.sh                    # Run all tests
  ./test-template-imports.sh --envDir-path /home/user/proof-environment
  ./test-template-imports.sh -s -a              # Run script and auto tests
  ./test-template-imports.sh -c                 # Run CLI test only
EOF
}

parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --envDir-path)
                # Convert to absolute path if relative
                PROOF_ENVIRONMENT_PATH=$(construct_abs_path $2)
                TEMPLATES_DIR="$PROOF_ENVIRONMENT_PATH/data/templates"
                DOCKER_COMPOSE_DIR="$PROOF_ENVIRONMENT_PATH/docker"
                PROOF_ENV_FILE="$DOCKER_COMPOSE_DIR/proof.env"
                shift 2
                ;;
            #-l)    # This option prevents the script from running after startPROOF call
            #    LOG_ARGUMENT="-l"
            #    shift
            #    ;;
            -g)
                GENERATE_COMPOSE_FILE=1
                shift
                ;;
            -ng)
                GENERATE_COMPOSE_FILE=0
                shift
                ;;
            --all)
                RUN_ALL_TESTS=1
                shift
                ;;
            -s|--script)
                RUN_SCRIPT_TEST=1
                shift
                ;;
            # -a|--auto)
            #     RUN_AUTO_TEST=1
            #     shift
            #     ;;
            # -c|--cli)
            #     RUN_CLI_TEST=1
            #     shift
            #     ;;
            -h|--help)
                print_usage
                exit 0
                ;;
            *)
                log_failure "Unknown option: $1"
                print_usage
                exit 1
                ;;
        esac
    done
    
    # If no argument provided, run all
    if [ $GENERATE_COMPOSE_FILE -eq -1 ] && [ $RUN_SCRIPT_TEST -eq 0 ] && [ $RUN_AUTO_TEST -eq 0 ] && [ $RUN_CLI_TEST -eq 0 ]; then
        RUN_ALL_TESTS=1
    fi

    if [ $GENERATE_COMPOSE_FILE -eq -1 ]; then
        GENERATE_COMPOSE_FILE=1
    fi

    if [ $RUN_ALL_TESTS -eq 1 ]; then
        RUN_SCRIPT_TEST=1
        #RUN_AUTO_TEST=1
        #RUN_CLI_TEST=1
    fi
}

validate_paths() {
    if [ ! -d "$PROOF_ENVIRONMENT_PATH" ]; then
        log_failure "proof-environment path not found: $PROOF_ENVIRONMENT_PATH"
        exit 1
    fi
    
    if [ ! -f "$DOCKER_COMPOSE_DIR/$DOCKER_COMPOSE_FILE" ]; then
        log_failure "docker-compose.dev.yaml not found: $DOCKER_COMPOSE_DIR/$DOCKER_COMPOSE_FILE"
        exit 1
    fi

    if [ ! -d "$PROOF_SCRIPTS_PATH" ]; then
        log_failure "PROOF scripts directory not found: $PROOF_SCRIPTS_PATH"
        exit 1
    fi
    
    if [ ! -d "$TEMPLATES_DIR" ]; then
        log_failure "Templates directory not found: $TEMPLATES_DIR"
        exit 1
    fi
    
    log_info "Configuration validated"
    log_info "  PROOF_ENVIRONMENT_PATH: $PROOF_ENVIRONMENT_PATH"
    log_info "  PROOF_SCRIPTS_PATH: $PROOF_SCRIPTS_PATH"
    log_info "  TEMPLATES_DIR: $TEMPLATES_DIR"
}

###############################################################################
# PROOF Service Management Functions
###############################################################################

check_proof_already_running() {
    if docker ps 2>/dev/null | grep -q "$TEST_POSTGRES_CONTAINER_NAME"; then
        return 0  # PROOF is running
    else
        return 1  # PROOF is not running
    fi
}

create_test_docker_compose() {
    local dev_docker_file_path="$DOCKER_COMPOSE_DIR/$DOCKER_COMPOSE_FILE"
    local test_docker_file_path="$DOCKER_COMPOSE_DIR/$TEST_DOCKER_COMPOSE_FILE"
    
    log_info "Creating test docker-compose file '$test_docker_file_path' with isolated database..."
    log_info "  Source: $dev_docker_file_path"
    
    # Verify source file exists before copying
    if [ ! -f "$dev_docker_file_path" ]; then
        log_failure "Source docker-compose file not found: $dev_docker_file_path"
        return 1
    fi

    # Copy the dev docker-compose and make volumes/networks test-specific
    if ! cp "$dev_docker_file_path" "$test_docker_file_path"; then
        log_failure "Failed to copy docker-compose file"
        return 1
    fi
    
    # Verify the copy succeeded
    if [ ! -f "$test_docker_file_path" ]; then
        log_failure "Test docker-compose file was not created: $test_docker_file_path"
        return 1
    fi
    
    # Rename postgres SERVICE from proof-postgres to test container name
    sed -i "s/^  proof-postgres:$/  $TEST_POSTGRES_CONTAINER_NAME:/" "$test_docker_file_path"
    
    # Update container_name field
    sed -i "s/container_name: proof-postgres/container_name: $TEST_POSTGRES_CONTAINER_NAME/" "$test_docker_file_path"
    
    # Update postgres volume mount references (in volumes section)
    sed -i "s/\"proof-postgres:/\"$TEST_POSTGRES_CONTAINER_NAME:/g" "$test_docker_file_path"
    
    # Update depends_on service references (handle multiple indentation levels)
    sed -i "s/proof-postgres:/$TEST_POSTGRES_CONTAINER_NAME:/g" "$test_docker_file_path"
    
    # Update healthcheck hostname reference
    sed -i "s/pg_isready -h proof-postgres/pg_isready -h $TEST_POSTGRES_CONTAINER_NAME/g" "$test_docker_file_path"
    
    # Update postgres volume definition names
    sed -i "s/name: proof-postgres$/name: $TEST_POSTGRES_VOLUME_NAME/" "$test_docker_file_path"
    sed -i "s/name: proof-postgres-importer-service$/name: $TEST_POSTGRES_VOLUME_NAME/" "$test_docker_file_path"
    
    # Rename proof-files volume to use isolated test volume
    sed -i "s/^  proof-files:$/  $TEST_FILES_VOLUME_NAME:/" "$test_docker_file_path"
    sed -i "s/name: proof-files$/name: $TEST_FILES_VOLUME_NAME/" "$test_docker_file_path"
    sed -i "s/\"proof-files:/\"$TEST_FILES_VOLUME_NAME:/g" "$test_docker_file_path"
    
    # Mark proof network as external (already exists)
    sed -i '/^networks:/,/^[^ ]/{/external: false/s/external: false/external: true/}' "$test_docker_file_path"
    
    log_info "Test docker-compose created: $test_docker_file_path"
}

start_proof() {
    log_section "Starting PROOF Services"
    
    # Run startPROOF from proof-environment directory with the full path to test compose file
    cd "$PROOF_ENVIRONMENT_PATH"
    
    # Start services - use timeout and capture output
    ./startPROOF -f "$TEST_DOCKER_COMPOSE_FILE" $LOG_ARGUMENT
    #if timeout 60 ./startPROOF -f "$TEST_DOCKER_COMPOSE_FILE" $LOG_ARGUMENT | head -100; then
        log_info "Services started, waiting for readiness..."
    #else
    #    log_failure "Failed to start docker compose services"
    #    return 1
    #fi

    # Wait for postgres
    log_info "Waiting for PostgreSQL to be ready..."
    local max_attempts=30
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        if timeout 5 docker exec $TEST_POSTGRES_CONTAINER_NAME psql -U "$DB_USER" "$DB_NAME" -c "SELECT 1;" &>/dev/null 2>&1; then
            log_success "PostgreSQL is ready"
            break
        fi
        echo -n "."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    if [ $attempt -ge $max_attempts ]; then
        log_failure "PostgreSQL did not become ready"
        return 1
    fi
    
    # Wait for config-manager API
    if ! wait_for_config_manager_api; then
        log_failure "proof-config-manager API did not become ready"
        return 1
    fi

    # Wait for importer API
    if ! wait_for_importer_api; then
        log_failure "proof-importer API did not become ready"
        return 1
    fi
    
    log_success "All services ready"
    return 0
}

stop_proof() {
    log_section "Stopping PROOF Services"
    
    cd "$PROOF_ENVIRONMENT_PATH"
    
    ./controlPROOF -f "$TEST_DOCKER_COMPOSE_FILE" -d -v
    #if docker compose -f "$TEST_DOCKER_COMPOSE_FILE" down 2>&1 | grep -v "^$"; then
    log_info "PROOF Services stopped"
    #fi
}    

cleanup() {
    # Cleanup test docker-compose file
    #if [ -f "$TEST_DOCKER_COMPOSE_FILE" ]; then
    #    rm -f "$TEST_DOCKER_COMPOSE_FILE"
    #    log_info "Test docker-compose file cleaned up"
    #fi
    
    # Clean up test database volume
    log_info "Cleaning up test database volume..."
    docker volume rm "$TEST_POSTGRES_VOLUME_NAME" 2>/dev/null || true
}

###############################################################################
# Database Functions
###############################################################################

# Query the database and return count of templates
get_template_count() {
    docker exec $TEST_POSTGRES_CONTAINER_NAME psql -U "$DB_USER" "$DB_NAME" -t -c \
        "SELECT COUNT(*) FROM template;" 2>/dev/null || echo "0"
}

# Get list of template IDs in database
get_template_ids() {
    docker exec $TEST_POSTGRES_CONTAINER_NAME psql -U "$DB_USER" "$DB_NAME" -t -c \
        "SELECT id FROM template ORDER BY id;" 2>/dev/null || echo ""
}

# Get list of template names in database
get_template_names() {
    docker exec $TEST_POSTGRES_CONTAINER_NAME psql -U "$DB_USER" "$DB_NAME" -t -c \
        "SELECT name FROM template ORDER BY name;" 2>/dev/null || echo ""
}

# Clear all templates from database
clear_templates() {
    #docker exec $TEST_POSTGRES_CONTAINER_NAME psql -U "$DB_USER" "$DB_NAME" -c \
    #    "DELETE FROM template;" 2>/dev/null
    sh $PROOF_SCRIPTS_PATH/clearPROOFDatabase
    log_info "Database cleared"
}

# Verify database connectivity
verify_db_connection() {
    if ! docker exec $TEST_POSTGRES_CONTAINER_NAME psql -U "$DB_USER" "$DB_NAME" -c "SELECT 1;" &>/dev/null; then
        log_failure "Cannot connect to database"
        return 1
    fi
    log_success "Database connection verified"
    return 0
}

###############################################################################
# Docker/Service Functions
###############################################################################

# Check if PROOF services are running (for within-test verification)
check_services_running() {
    local required_services=("$TEST_POSTGRES_CONTAINER_NAME" "proof-config-manager" "proof-importer")
    
    for service in "${required_services[@]}"; do
        if ! docker ps 2>/dev/null | grep -q "$service"; then
            return 1
        fi
    done
    
    return 0
}

# Wait for proof-importer API to be ready
wait_for_importer_api() {
    local max_attempts=30
    local attempt=0
    
    log_info "Waiting for proof-importer REST API..."
    
    while [ $attempt -lt $max_attempts ]; do
        if timeout 3 curl -s -m 2 -X POST "http://localhost:8090/v1/import/template" &>/dev/null; then
            log_success "proof-importer API is ready"
            return 0
        fi
        echo -n "."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    echo ""
    log_failure "proof-importer API did not become ready"
    return 1
}

# Wait for proof-config-manager API to be ready
wait_for_config_manager_api() {
    local max_attempts=30
    local attempt=0
    
    log_info "Waiting for proof-config-manager API..."
    
    while [ $attempt -lt $max_attempts ]; do
        if timeout 3 curl -s -m 2 "http://localhost:8100/v1/health" &>/dev/null; then
            log_success "proof-config-manager API is ready"
            return 0
        fi
        echo -n "."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    echo ""
    log_failure "proof-config-manager API did not become ready"
    return 1
}

###############################################################################
# Test Method 1: Manual Bootstrap Script
###############################################################################

test_manual_bootstrap() {
    log_section "Test 1: Manual Startup Import Script (startup-import.sh)"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    # Check prerequisites
    if ! check_services_running; then
        log_failure "Test 1: Required services not running"
        return 1
    fi
    
    if ! verify_db_connection; then
        log_failure "Test 1: Database not accessible"
        return 1
    fi
    
    # Clear database for clean test
    clear_templates
    local before_count=$(get_template_count | tr -d ' ')
    
    # Run startup import script
    log_info "Running startup-import.sh..."
    if cd "$DOCKER_COMPOSE_DIR" && ./scripts/startup-import.sh &>/dev/null; then
        log_info "Startup import script completed"
    else
        log_failure "Test 1: Startup import script execution failed"
        return 1
    fi
    
    # Verify results
    local after_count=$(get_template_count | tr -d ' ')
    local templates_added=$((after_count - before_count))
    
    if [ "$templates_added" -ge 11 ]; then
        log_success "Test 1: All 11 templates imported (Before: $before_count, After: $after_count)"
        log_info "Imported templates:"
        get_template_names | sed 's/^/  - /'
        return 0
    else
        log_failure "Test 1: Expected 11+ templates, got $templates_added (Before: $before_count, After: $after_count)"
        return 1
    fi
}

###############################################################################
# Test Method 2: Automatic Docker Compose Import
###############################################################################

test_automatic_docker_compose() {
    log_section "Test 2: Automatic Docker Compose Import (--profile import-templates)"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    if ! verify_db_connection; then
        log_failure "Test 2: Database not accessible"
        return 1
    fi
    
    # Clear database for clean test
    clear_templates
    local before_count=$(get_template_count | tr -d ' ')
    
    # Start automatic import service using the profile
    log_info "Starting docker compose with import-templates profile..."
    cd "$DOCKER_COMPOSE_DIR"
    
    if timeout 60 docker compose -f "$TEST_DOCKER_COMPOSE_FILE" --env-file proof.env --profile import-templates up -d proof-templates-importer 2>&1 | head -10; then
        log_info "Import service started"
    else
        log_failure "Test 2: Failed to start import service"
        return 1
    fi
    
    # Wait for automatic import to complete
    log_info "Waiting for automatic import service to complete (90 seconds)..."
    sleep 90
    
    # Verify results
    local after_count=$(get_template_count | tr -d ' ')
    local templates_added=$((after_count - before_count))
    
    if [ "$templates_added" -ge 11 ]; then
        log_success "Test 2: All 11 templates imported via automatic service (Before: $before_count, After: $after_count)"
        log_info "Imported templates:"
        get_template_names | sed 's/^/  - /'
        
        # Clean up the import service
        timeout 30 docker compose -f "$TEST_DOCKER_COMPOSE_FILE" --env-file proof.env down proof-templates-importer 2>&1 || true
        
        return 0
    else
        log_failure "Test 2: Expected 11+ templates, got $templates_added (Before: $before_count, After: $after_count)"
        return 1
    fi
}

###############################################################################
# Test Method 3: Manual CLI Import via startImporter
###############################################################################

test_manual_cli_import() {
    log_section "Test 3: Manual CLI Import (startImporter script)"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    if ! verify_db_connection; then
        log_failure "Test 3: Database not accessible"
        return 1
    fi
    
    # Clear database for clean test
    clear_templates
    local before_count=$(get_template_count | tr -d ' ')
    
    # Check if startImporter script exists
    if [ ! -f "./startImporter" ]; then
        log_warn "Test 3: startImporter script not found - skipping CLI test"
        return 0
    fi
    
    # Import a specific template using CLI
    log_info "Testing CLI import for a single template (Adder)..."
    
    # Get the JAR file
    local jar_file=$(find ./target -name "proof-importer-*.jar" -type f | head -1)
    if [ -z "$jar_file" ]; then
        log_failure "Test 3: No proof-importer JAR found in target directory"
        return 1
    fi
    
    # Run CLI import
    if java -jar "$jar_file" --template="$TEMPLATES_DIR/Adder.json" --save=true 2>&1 | grep -q "Import completed: SUCCESS"; then
        log_info "CLI import command executed"
    else
        log_failure "Test 3: CLI import command failed"
        return 1
    fi
    
    # Verify results
    sleep 2
    local after_count=$(get_template_count | tr -d ' ')
    local templates_added=$((after_count - before_count))
    
    if [ "$templates_added" -ge 1 ]; then
        log_success "Test 3: Successfully imported $templates_added template(s) via CLI (Before: $before_count, After: $after_count)"
        log_info "Imported templates:"
        get_template_names | sed 's/^/  - /'
        return 0
    else
        log_failure "Test 3: Expected at least 1 template, got $templates_added (Before: $before_count, After: $after_count)"
        return 1
    fi
}

###############################################################################
# Main Test Execution
###############################################################################

main() {
    echo -e "${BLUE}"
    cat << "EOF"
╔══════════════════════════════════════════════════════════════════════════╗
║                   PROOF Template Import Test Suite                       ║
║                                                                          ║
║  Tests all three template import methods:                                ║
║  1. Manual Startup Import Script (startup-import.sh)                     ║
║  2. Automatic Docker Compose (--profile import-templates)                ║
║  3. Manual CLI Import (startImporter)                                    ║
╚══════════════════════════════════════════════════════════════════════════╝
EOF
    echo -e "${NC}"
    
    # Parse command line arguments
    parse_arguments "$@"
    
    # Validate paths
    validate_paths
    
    # Generate the compose file (independent of PROOF)
    if [ $GENERATE_COMPOSE_FILE -eq 1 ]; then
        log_info "Generating test docker-compose file..."
        if ! create_test_docker_compose; then
            log_failure "Failed to create test docker-compose file"
            return 1
        fi
    fi

    if [ $RUN_SCRIPT_TEST -eq 0 ] && [ $RUN_AUTO_TEST -eq 0 ] && [ $RUN_CLI_TEST -eq 0 ]; then
        log_section "No tests requested."
        return 0
    fi

    # Check if PROOF is already running
    log_section "Pre-Flight Checks"
    local enableProofStart=1
    
    if check_proof_already_running; then
        log_warn "PROOF is already running with production database"
        log_warn "This test requires a dedicated database to avoid conflicts"
        log_info "Please stop PROOF first:"
        log_info "  cd $PROOF_ENVIRONMENT_PATH && ./controlPROOF -s"
        enableProofStart=0
        #exit 1
        log_success "Using running PROOF services"
    fi
    
    # Start PROOF with test database 
    if [ $enableProofStart -eq 1 ]; then
        log_success "No existing PROOF services detected"
        
        if ! start_proof; then
            log_failure "Failed to start PROOF services"
            stop_proof
            exit 1
        fi
    fi

    # Run selected tests
    log_section "Running Tests"
    echo "RUN_SCRIPT_TEST:  $RUN_SCRIPT_TEST"
    echo "RUN_AUTO_TEST:    $RUN_AUTO_TEST"
    echo "RUN_CLI_TEST:     $RUN_CLI_TEST"

    [ $RUN_SCRIPT_TEST -eq 1 ] && test_manual_bootstrap
    [ $RUN_AUTO_TEST -eq 1 ] && test_automatic_docker_compose
    [ $RUN_CLI_TEST -eq 1 ] && test_manual_cli_import
    
    # Stop PROOF and cleanup
    if [ $enableProofStart -eq 1 ]; then
        stop_proof
    fi

    ## Todo: check if it's necessary
    #cleanup

    # Print summary
    log_section "Test Summary"
    
    echo ""
    echo -e "Total Tests Run:    $TOTAL_TESTS"
    echo -e "Tests Passed:       ${GREEN}$PASSED_TESTS${NC}"
    echo -e "Tests Failed:       ${RED}$FAILED_TESTS${NC}"
    echo ""
    
    # Final verification: Show all templates in database
    log_info "Final Database State:"
    local final_count=$(get_template_count | tr -d ' ')
    echo -e "  Total Templates: ${GREEN}$final_count${NC}"
    
    if [ "$final_count" -gt 0 ]; then
        echo ""
        echo "  Template List:"
        get_template_ids | sed 's/^/    /'
    fi

    echo ""
    
    if [ $FAILED_TESTS -eq 0 ]; then
        log_success "All tests completed successfully!"
        exit 0
    else
        log_failure "Some tests failed"
        exit 1
    fi

    echo ""
}

# Improve signal handling
cleanup_on_interrupt() {
    echo ""
    log_failure "Script interrupted by user"
    # Force kill docker compose if it's stuck
    pkill -f "docker compose.*TEST_postgres-proof-importer-unittest" 2>/dev/null || true
    stop_proof
    exit 1
}

trap cleanup_on_interrupt INT TERM

# Run main function
main "$@"
