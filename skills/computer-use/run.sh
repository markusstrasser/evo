#!/usr/bin/env bash
# Computer Use Skill - MCP-based computer control helpers
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$HOME/Projects/computer-use-mcp"

usage() {
    cat <<EOF
Computer Use Skill - MCP-based computer control helpers

Note: The MCP server auto-starts with Claude Code (configured in .mcp.json)

Usage:
    $0 status                Check if MCP is connected
    $0 info                  Get display information
    $0 help                  Show this help

Examples:
    $0 status                # Check MCP connection
    $0 info                  # Show display info

Server location: $SERVER_DIR
MCP config: .mcp.json
EOF
}

check_status() {
    echo "Computer Use MCP Status"
    echo "======================="

    if [[ ! -d "$SERVER_DIR" ]]; then
        echo "✗ Server directory not found: $SERVER_DIR"
        echo "  Setup required at ~/Projects/computer-use-mcp/"
        return 1
    fi

    echo "✓ Server directory exists: $SERVER_DIR"
    echo ""
    echo "MCP Configuration:"
    echo "  Config file: .mcp.json"
    echo "  Server type: auto-start with Claude Code"
    echo ""
    echo "To verify MCP connection, run: /mcp"
    echo "Available tools: mcp__computer-use__screenshot, mcp__computer-use__left_click, etc."
}

get_info() {
    if [[ ! -d "$SERVER_DIR" ]]; then
        echo "✗ Server directory not found: $SERVER_DIR"
        exit 1
    fi

    echo "Computer Use MCP Server Info"
    echo "=============================="
    echo "Server location: $SERVER_DIR"
    echo "Platform: $(uname -s)"
    echo "Python: $(cd "$SERVER_DIR" && uv run python --version 2>/dev/null || echo 'Not installed')"
    echo ""
    echo "Display info:"
    if [[ "$(uname -s)" == "Darwin" ]]; then
        system_profiler SPDisplaysDataType | grep -E "Resolution|Display Type" || echo "  Unable to get display info"
    else
        xdpyinfo 2>/dev/null | grep dimensions || echo "  Unable to get display info"
    fi
}

# Main command dispatcher
case "${1:-}" in
    status)
        check_status
        ;;
    info)
        get_info
        ;;
    help|--help|-h|"")
        usage
        ;;
    *)
        echo "✗ Unknown command: $1"
        echo ""
        usage
        exit 1
        ;;
esac
