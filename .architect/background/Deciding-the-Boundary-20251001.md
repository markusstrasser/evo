Deciding the Boundary

  - Think in terms of who drives the action and how much orchestration is required. If an LLM agent or remote UI must invoke logic safely, discover parameters, stream progress, or resume context, expose it as an MCP
  tool. Local developers who need ad‑hoc, fast commands without protocol overhead can keep plain scripts.
  - Complexity vs dependencies: once a workflow needs real data structures (EDN/JSON), conditional branching, retries, or shared Clojure utilities, move it into babashka/CLJ code; bash stays only for tiny glue
  tasks.
  - Reuse & consistency: logic that should behave identically whether called from CLI, MCP, or tests should live in a single implementation (e.g. babashka namespace) and be wrapped by thin shells/MCP adapters. The
  wrappers become boundary layers, the core stays language-native.

  When to Stay with Scripts

  - bash for one-liners or near-shell-native tasks (file perms, copying hook files) where spawning Clojure/Python would be overkill and there’s no desire for structured outputs (scripts/install-hooks.sh:10).
  - Babashka scripts when the main user is a developer at the terminal, the task benefits from Malli validation or access to shared namespaces, but you don’t need network exposure or protocol negotiation. For
  example, environment setup or bb env:check that developers run locally.

  When to Prefer MCP Tools

  - Workflows you expect LLM agents to trigger autonomously or from a remote interface: research runs, proposal generation, battle-test synthesis, health checks. MCP gives typed schemas, progress callbacks,
  cancellation, and safe parameter discovery.
  - Scenarios requiring multi-step conversations or result streaming: analyze_battle_test benefits from progress reporting and structured final payloads.
  - Shared context resources (docs, latest overviews) so agents can request them without raw filesystem access.

  How to Structure Both

  - Core logic in babashka/Clojure namespaces for tasks rooted in the Clojure ecosystem (EDN manipulations, repo scans). Provide a bb.edn task for local use and call the same namespace from an MCP tool via the JVM
  or by shelling to bb with deterministic arguments.
  - For Python/FastMCP tools wrapping shell or babashka commands, keep adapters thin: validate inputs, call the shared implementation, translate outputs to MCP responses, and emit progress via ctx.report_progress.
  - Maintain scripts as convenience front-ends: e.g., a scripts/research-run shim can call bb research:run (which in turn shares code with mcp.tool("run_research")). Developers get quick CLI usage; agents go through
  MCP.

  Quick Heuristics

  - Agent-triggered, structured response, or remote execution required? → MCP tool.
  - Reusable logic, heavy EDN/DB use, developer-first? → Babashka/CLJ module, optionally wrapped by MCP.
  - Pure shell plumbing, minimal logic, human-only? → Keep as bash script.

  Natural next steps: align one representative workflow (e.g. research orchestrator) to this layering, ensure both bb and MCP wrappers call the same core code, and trim the remaining bash scripts to true glue-only
  cases.
