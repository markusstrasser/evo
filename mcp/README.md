# MCP Servers

MCP servers for the evo project, exposing dev tooling and future capabilities to Claude Code.

## Directory Structure

```
mcp/
├── servers/              # Clojure MCP servers
│   └── dev_diagnostics.clj
├── eval/                 # Python: Tournament evaluation server
├── review/               # Python: Review workflow server
├── shared/               # Shared utilities (future)
├── config/               # Example configs (future)
├── requirements.txt      # Python dependencies
└── README.md
```

## Setup

### Python Servers (eval, review)

```bash
# Create virtual environment (one-time)
python3 -m venv .venv

# Activate it
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt
```

### Clojure Servers (dev-diagnostics)

No setup needed - uses project Clojure deps.

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

### eval (evo-eval)

Tournament-based evaluation server using Swiss-Lite algorithm with AI judges.

**Tools**:
- `compare_items` - Compare 2 items with AI judges
- `compare_multiple` - Tournament ranking for 3+ items

**Location**: `mcp/eval/`
**Docs**: See `mcp/eval/eval_server.py`

### review (review-flow)

Minimal-linear review workflow: idea → proposals → ranking → decision.

**Tools**:
- `propose` - Generate 3 proposals (gemini/codex/grok)
- `rank_proposals` - Tournament evaluation (mounts evo-eval)
- `refine` - Feedback loops with validation
- `decide` - Record ADR
- `review_cycle` - One-shot: generate → rank → decide

**Resources**:
- `review://runs/{id}` - Run state
- `review://ledger` - Provenance log (JSONL)

**Location**: `mcp/review/`
**Docs**: See `mcp/review/README.md`

## Future

- `shared/` - Reusable MCP utilities (Mono patterns, tool helpers)
- `config/` - Example server configs
- Research automation as MCP tools
- Resources for dev state
- Prompts as workflows
