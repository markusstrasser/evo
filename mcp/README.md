# MCP Servers

MCP servers for the evo project, exposing dev tooling and future capabilities to Claude Code.

## Directory Structure

```
mcp/
├── servers/              # MCP server implementations
│   └── dev_diagnostics.clj
├── shared/               # Shared utilities (future)
├── config/               # Example configs (future)
└── README.md
```

## Active Servers

### dev-diagnostics

Minimal MCP server exposing dev tooling via Java MCP SDK.

**Tools**:
1. `evo_health_check` - Comprehensive health checks (shadow-cljs, REPL, caches)
2. `evo_cache_stats` - Cache size statistics
3. `evo_clear_caches` - Clear shadow-cljs caches (requires rebuild)
4. `evo_repl_health` - Quick REPL diagnostic

**Location**: `mcp/servers/dev_diagnostics.clj`

## Usage

### Test locally

```bash
clj -M:mcp

# Should see:
# Starting evo dev-diagnostics MCP server...
# ✓ Server configured with 4 dev tools
# Ready for Claude Code connection...
```

### Connect to Claude Code

Server is configured in `.mcp.json`:

```json
{
  "mcpServers": {
    "evo-dev": {
      "type": "stdio",
      "command": "clj",
      "args": ["-M:mcp"]
    }
  }
}
```

Or add via global `~/.claude.json` (see `docs/MCP.md` for details).

### Use in Claude Code

```
/mcp                           # Check server status
> Check my dev environment     # Calls evo_health_check
> What are my cache sizes?     # Calls evo_cache_stats
```

## Development

### Add a new tool

1. Define function in `servers/dev_diagnostics.clj`:
```clojure
(defn my-new-tool [_params]
  {:result "success"})
```

2. Register in `tool-implementations`:
```clojure
(def tool-implementations
  {"evo_my_new_tool" my-new-tool
   ...})
```

3. Add to `-main`:
```clojure
(create-tool "evo_my_new_tool" "Description")
```

4. Restart: `clj -M:mcp`

### Test in REPL

```clojure
(require '[mcp.servers.dev-diagnostics :as mcp])
(mcp/health-check-tool {})
(mcp/cache-stats-tool {})
```

## Architecture

```
Claude Code (MCP client)
  ↓ stdio (JSON-RPC)
mcp.servers.dev-diagnostics
  ↓ function calls
dev/health.clj, dev/session.clj
```

**Stack**: Java MCP SDK + Clojure + Project Reactor (Mono patterns)

See `docs/MCP.md` for complete reference (config, failure modes, patterns).

## Future

- `shared/` - Reusable MCP utilities (Mono patterns, tool helpers)
- `config/` - Example server configs
- Research automation as MCP tools
- Resources for dev state
- Prompts as workflows
