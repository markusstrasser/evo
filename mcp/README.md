# MCP Servers

MCP servers are now in standalone repos under `~/Projects/`:

- **tournament-mcp** (`~/Projects/tournament-mcp/`) - Tournament-based LLM judge comparison
- **architect-mcp** (`~/Projects/architect-mcp/`) - Architectural decision-making workflow

This directory (`mcp/`) now only contains Clojure-specific MCP servers for the evo project.

## Active Server: dev-diagnostics

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
    "mcp-shadow-dual": {
      "type": "local",
      "command": "/opt/homebrew/bin/bash",
      "args": ["-c", "clojure -X:mcp-shadow-dual"]
    }
  }
}
```

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

## Standalone Python MCPs

These have been moved to separate repos for easier maintenance and reuse:

### tournament-mcp

**Location**: `~/Projects/tournament-mcp/`

Tournament-based LLM judge comparison with Bradley-Terry ranking.

**Tools**:
- `compare_items` - Compare 2 items with AI judges
- `compare_multiple` - Tournament ranking for 3+ items

**Prompts**:
- `/code_comparison` - Code evaluation template
- `/writing_comparison` - Writing evaluation template

**Features**:
- Progress reporting
- Markdown export
- Quality metrics (R², τ-split, brittleness, dispersion)
- Bias detection

**Docs**: See `~/Projects/tournament-mcp/README.md`

### architect-mcp

**Location**: `~/Projects/architect-mcp/`

Architectural decision-making workflow: proposals → tournament → ADR.

**Tools**:
- `propose` - Generate proposals (gemini/codex/grok)
- `rank_proposals` - Tournament evaluation (mounts tournament-mcp)
- `refine` - Feedback loops with validation
- `decide` - Record ADR
- `review_cycle` - One-shot: generate → rank → decide
- Research management tools

**Resources**:
- `review://runs/{id}` - Run state
- `review://ledger` - Provenance log (JSONL)

**Docs**: See `~/Projects/architect-mcp/README.md`

## Configuration

All MCPs are configured in `.mcp.json`:

```json
{
  "mcpServers": {
    "mcp-shadow-dual": {
      "type": "local",
      "command": "/opt/homebrew/bin/bash",
      "args": ["-c", "clojure -X:mcp-shadow-dual"]
    },
    "tournament": {
      "type": "local",
      "command": "uv",
      "args": [
        "--directory",
        "/Users/alien/Projects/tournament-mcp",
        "run",
        "fastmcp",
        "run",
        "eval_server.py"
      ]
    },
    "architect": {
      "type": "local",
      "command": "uv",
      "args": [
        "--directory",
        "/Users/alien/Projects/architect-mcp",
        "run",
        "python",
        "-m",
        "__main__"
      ]
    }
  }
}
```
