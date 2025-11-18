
Quick reference for Model Context Protocol (MCP) integration with Claude Code.

## Quick Start

```bash
/mcp                                    # Check status
(+ 1 2 3)                              # REPL via clojure-mcp => 6
```

## Configuration

**`.mcp.json`** (project):
```json
{
  "mcpServers": {
    "mcp-shadow-dual": {
      "type": "local",
      "enabled": true,
      "command": "/opt/homebrew/bin/bash",
      "args": ["-c", "clojure -X:mcp-shadow-dual"]
    }
  }
}
```

**`~/.claude.json`** (global/project-scoped):
```json
{
  "projects": {
    "/Users/alien/Projects/evo": {
      "mcpServers": {
        "evo-dev": {
          "type": "stdio",
          "command": "clj",
          "args": ["-M:mcp"]
        }
      }
    }
  }
}
```

**`deps.edn`**:
```clojure
:mcp {:extra-paths ["mcp" "dev"]
      :extra-deps {io.modelcontextprotocol.sdk/mcp {:mvn/version "0.12.1"}
                   org.clojure/data.json {:mvn/version "2.5.1"}
                   org.slf4j/slf4j-simple {:mvn/version "2.0.9"}}
      :main-opts ["-m" "evo.mcp.dev-diagnostics"]}

:mcp-shadow-dual {:extra-deps {io.github.bhauman/clojure-mcp {:git/sha "2dd09e595df7bb9b01a7c811955a354777253dc2"}}
                  :exec-fn clojure-mcp.main/start-mcp-server
                  :exec-args {:port 55449}}
```

**Precedence**: Project `.mcp.json` > Project-scoped `~/.claude.json` > Global

## Active Servers

**Main Agent MCPs:**

1. **clojure-shadow-cljs** - Full REPL + editing
   - nREPL via shadow-cljs (port 55449)
   - Usage: `(require '[core.db :as db])`

2. **chrome-devtools** - Browser control
   - DevTools Protocol: JS exec, console, DOM

3. **tournament** - Bradley-Terry ranking
   - Swiss-Lite tournaments for evaluations

4. **computer-use** - Computer control
   - Screenshots, mouse, keyboard actions
   - Visual debugging and UI testing

5. **context7** - Library documentation
   - Up-to-date docs for any library
   - Usage: `resolve-library-id` → `get-library-docs`
   - Inherited by researcher subagent

6. **exa** - Code examples and web search
   - Real-world usage patterns
   - Tools: `web_search_exa`, `get_code_context_exa`
   - Inherited by researcher subagent

**Researcher Subagent:**
- Inherits all MCPs from main agent
- See: `.claude/agents/researcher.md` for configuration
- Note: Subagents cannot have MCPs that main agent doesn't have

**Skills:**
- `ck` CLI - Code search (semantic, regex, hybrid)
- `skills/computer-use/` - MCP helper commands (status, info)
- `skills/architect/` - Proposal generation and evaluation

**Removed MCPs:**
- `ck-search` - Use `ck` CLI directly
- `evo-dev` - Deprecated (use `bb health` instead)
- `architect` - Now a skill at `skills/architect/`

## Critical: Stdio Logging

**NEVER write to stdout** (corrupts JSON-RPC):

```clojure
;; ❌ FATAL
(println "Debug")

;; ✅ CORRECT
(binding [*out* *err*] (println "Debug"))
(.println System/err "Status")
```

## Common Failures

1. **Server Won't Connect** → Missing alias / wrong path / port conflict → Test: `clj -M:mcp`
2. **ClassNotFoundException** → Wrong package: use `.server.*` not `.sdk.*` → Verify jar
3. **FileNotFoundException** → Missing `:extra-paths ["mcp" "dev"]`
4. **Shadow Port Drift** → 55449→55450 on restart → Fix port in `shadow-cljs.edn` or update deps.edn
5. **No Method: builder** → Use `(McpServer/async transport)` not `.builder`
6. **CLJS "No namespace: js"** → Wrong REPL → `(shadow.cljs.devtools.api/nrepl-select :app)`
7. **"No JS runtime"** → No browser → Open `localhost:8080` or use chrome-devtools MCP

## Java MCP SDK Pattern

```clojure
(import '[io.modelcontextprotocol.server McpServer]
        '[io.modelcontextprotocol.server.transport StdioServerTransportProvider]
        '[io.modelcontextprotocol.spec McpSchema$Tool McpSchema$CallToolResult
          McpSchema$TextContent McpSchema$ServerCapabilities]
        '[reactor.core.publisher Mono])

;; Tool handler using Mono/Consumer pattern
(defn create-tool-handler [tool-fn]
  (fn [_exchange arguments]
    (Mono/create
     (reify java.util.function.Consumer
       (accept [_this sink]
         (try
           (let [result (tool-fn arguments)
                 content (McpSchema$TextContent. (json/write-str result))
                 tool-result (McpSchema$CallToolResult. [content] false)]
             (.success sink tool-result))
           (catch Exception e (.error sink e))))))))

;; Server creation
(-> (McpServer/async (StdioServerTransportProvider. (ObjectMapper.)))
    (.serverInfo "name" "0.1.0")
    (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
    (.build))
```

## MCP Primitives

**Servers**: Tools (actions), Resources (read-only data), Prompts (templates)
**Clients**: Sampling (LLM calls), Elicitation (user input), Logging

## Dev Workflow

**Add evo-dev tool**:
```clojure
(defn my-tool [_] {:ok true})
(def tool-implementations {"evo_my_tool" my-tool})
;; Add spec in -main, restart: clj -M:mcp
```

**Update shadow port**: Note from `npm run dev` → Update `:exec-args {:port ...}` → `/mcp` reconnect

## References

**Local**: `~/Projects/best/modelcontextprotocol/docs/` (121 MDX files)
- `docs/learn/architecture.mdx` - Core concepts
- `docs/develop/build-server.mdx` - Server guide
- `specification/` - Protocol specs

**Online**:
- [MCP Spec](https://spec.modelcontextprotocol.io/)
- [clojure-mcp](https://github.com/bhauman/clojure-mcp)
- [FastMCP](https://github.com/jlowin/fastmcp) (Python, cleaner than Java)
- [Java SDK](https://github.com/modelcontextprotocol/java-sdk)

**Key Lessons**: Use clojure-mcp for REPL · Use Python/FastMCP for custom tools · Classes in `.server.*` not `.sdk.*` · Test locally first · Never hardcode ports
