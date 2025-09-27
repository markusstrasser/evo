#!/bin/bash

# Robust condition waiting to replace all sleep/timeout patterns
# Eliminates race conditions and timing-dependent failures

wait_for_condition() {
    local description="$1"
    local check_command="$2"
    local timeout_seconds="${3:-30}"
    local check_interval="${4:-1}"
    
    echo "⏳ Waiting for: $description"
    
    local elapsed=0
    while [ $elapsed -lt $timeout_seconds ]; do
        if eval "$check_command" >/dev/null 2>&1; then
            echo "✅ $description (after ${elapsed}s)"
            return 0
        fi
        
        sleep $check_interval
        elapsed=$((elapsed + check_interval))
        
        # Show progress every 5 seconds
        if [ $((elapsed % 5)) -eq 0 ]; then
            echo "   ... still waiting (${elapsed}s/${timeout_seconds}s)"
        fi
    done
    
    echo "❌ Timeout waiting for: $description (${timeout_seconds}s)"
    return 1
}

# Predefined common conditions
wait_for_server() {
    local url="$1"
    local timeout="${2:-30}"
    wait_for_condition "Server at $url" "curl -s --connect-timeout 2 '$url' >/dev/null" "$timeout"
}

wait_for_file() {
    local filepath="$1"
    local timeout="${2:-30}"
    wait_for_condition "File $filepath" "test -f '$filepath'" "$timeout"
}

wait_for_compilation() {
    local build_name="$1"
    local timeout="${2:-60}"
    wait_for_condition "Build $build_name completion" "test -f 'out/tests.js' || test -f 'out/ui-tests/main.js'" "$timeout"
}

wait_for_port() {
    local port="$1"
    local timeout="${2:-30}"
    wait_for_condition "Port $port availability" "nc -z localhost $port" "$timeout"
}

# Export functions for use in other scripts
export -f wait_for_condition wait_for_server wait_for_file wait_for_compilation wait_for_port