---
name: Computer Use
description: Control computer via screenshots, mouse, and keyboard through MCP. Auto-starts with Claude Code. Enables visual debugging, UI testing, and browser automation. Requires platform-specific dependencies (pyobjc on macOS, python-xlib on Linux). Use for GUI testing, visual validation, and tasks requiring direct computer control.
---

# Computer Use Skill

MCP-based computer control via screenshots, mouse, and keyboard actions. Auto-connects when Claude Code starts.

## Quick Start

```bash
# Check if MCP is connected
/mcp

# Get display info (via MCP tools directly)
# Use mcp__computer-use__get_display_info

# Or use helper commands
skills/computer-use/run.sh status
skills/computer-use/run.sh info
```

## MCP Integration

**Auto-configured**: The MCP server is defined in `.mcp.json` and auto-starts with Claude Code.

**Available MCP Tools:**
- `mcp__computer-use__screenshot` - Capture display
- `mcp__computer-use__left_click` - Click at coordinates
- `mcp__computer-use__type_text` - Type text
- `mcp__computer-use__key` - Press keys
- `mcp__computer-use__mouse_move` - Move cursor
- `mcp__computer-use__get_display_info` - Get system info
- Plus enhanced actions (scroll, drag, double-click, etc.)

## Helper Commands

### `status` - Check MCP Status

Check if computer-use MCP is connected.

```bash
skills/computer-use/run.sh status
```

### `info` - Display Information

Get system and display information (wrapper around platform tools).

```bash
skills/computer-use/run.sh info
```

## Available Actions

### Basic Actions
- `screenshot` - Capture current display as base64-encoded PNG
- `left_click` - Click at specified coordinates with validation
- `type_text` - Type text strings with progress tracking
- `key` - Press keys or combinations (e.g., `ctrl+s`, `enter`)
- `mouse_move` - Move cursor to coordinates

### Enhanced Actions (computer_20250124)
- `scroll` - Scroll in any direction with amount control
- `left_click_drag` - Click and drag between coordinates
- `right_click`, `middle_click` - Additional mouse buttons
- `double_click`, `triple_click` - Multiple clicks
- `left_mouse_down`, `left_mouse_up` - Fine-grained click control
- `wait` - Pause between actions (0-10000ms)

### Configuration
- `get_display_info` - System and display information
- `set_display_size` - Configure display resolution (640x480 to 1920x1080)

### Resources
- `computer://display/config` - Current display configuration
- `computer://capabilities` - Available actions and platform info
- `computer://system/info` - Detailed system information

## Implementation

**Location:** `~/Projects/computer-use-mcp/`

Built with:
- **FastMCP** - Modern MCP server framework
- **uv** - Fast Python package manager
- **Pydantic** - Type-safe parameter validation

## Platform-Specific Setup

### macOS
```bash
cd ~/Projects/computer-use-mcp
uv add pyobjc-core pyobjc-framework-Quartz
```

### Linux
```bash
cd ~/Projects/computer-use-mcp
uv add python-xlib
sudo apt-get install python3-xlib python3-dev scrot
```

## Usage Examples

### Visual Testing
```bash
# Take a screenshot
skills/computer-use/run.sh screenshot > /tmp/screen.png

# Click at coordinates
skills/computer-use/run.sh click 100 200

# Type text
skills/computer-use/run.sh type "Hello World"
```

### Browser Automation
```bash
# Open browser (via keyboard shortcut)
skills/computer-use/run.sh key "cmd+space"
skills/computer-use/run.sh type "Safari"
skills/computer-use/run.sh key "enter"
```

## Notes

- **Permissions**: macOS requires Accessibility permissions for mouse/keyboard control
- **Display**: Works with primary display only
- **Performance**: Screenshot capture is ~50-100ms on macOS
- **Safety**: All coordinates are validated against display bounds

## See Also

- [Visual Validation Skill](../visual/SKILL.md) - For comparing screenshots
- [REPL Debug Skill](../repl-debug/SKILL.md) - For debugging browser state
- Implementation: `~/Projects/computer-use-mcp/README.md`
