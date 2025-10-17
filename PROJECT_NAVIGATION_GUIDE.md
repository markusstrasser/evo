# Project: Evolver

## Quick Start
- **Install**: `npm install` to set up Node.js dependencies and Clojure tools.
- **Run**: `npm run dev` to start the shadow-cljs watcher and dev server.
- **Test**: `npm test` to run the test suite.

## Project Structure
- **`.architect/`**: Architectural Decision Records (ADRs) and research background. The "why" behind technical decisions.
- **`dev/`**: Development-time utilities, including REPL helpers (`repl/`), health checks (`bin/`), and test fixtures.
- **`docs/`**: High-level documentation on workflows, patterns, and specifications.
- **`mcp/`**: Model Context Protocol (MCP) server definitions for agent integration.
- **`scripts/`**: Shell scripts for automation and tasks.
- **`skills/`**: Self-contained agent capabilities with progressive disclosure (docs, scripts, config).
- **`public/`**: Static assets and HTML files for browser-based UIs.
- **`resources/`**: EDN data files used for tests/fixtures.

## Development Workflow
- **REPL**: The primary workflow is REPL-driven. Start with `npm run dev`, then connect your editor to the nREPL on port `7888` (defined in `deps.edn`). The shadow-cljs REPL is on port `55449`.
- **Testing**: Run tests via `npm test`. The test build is configured in `shadow-cljs.edn` under the `:test` profile.
- **Quality Gates**: Pre-commit checks (`.pre-commit-check.sh`) run `clj-kondo` for linting and a shadow-cljs compilation check. Run manually with `npm run lint` or `npm run check`.
- **Debugging**: Use helpers in `dev/debug.cljs` in the browser console. See `docs/CHROME_DEVTOOLS.md` for MCP-specific debugging.

## Documentation & Context
- **Architectural Decisions**: Find ADRs in `.architect/adr/`.
- **Research Materials**: Background research is in `.architect/background/` and `docs/`.
- **Specs**: `.architect/specs/`.
- **Workflows**: `docs/workflows/`.
- **Agent/Tooling Docs**: `CLAUDE.md`, `DEV.md`, `MCP.md`.
- **Tool Discovery**: `dev/tooling-index.edn` is the central registry for all tools (MCPs, Skills, CLIs).

## Tooling & Automation
- **NPM Scripts**: Key scripts in `package.json` include `dev`, `test`, `lint`, `check`, `fix:cache`, and `agent:health`.
- **Shell Scripts**: `scripts/` and `dev/bin/` contain numerous automation scripts. Key ones are `generate-overview.sh`, `install-hooks.sh`, and various health checks.
- **Babashka Tasks**: `bb.edn` defines tasks for running research and evaluation workflows (e.g., `bb analyze-battle-test`).
- **Git Hooks**: A pre-commit hook is defined in `scripts/hooks/pre-commit`. Install with `scripts/install-hooks.sh`.
- **MCP Servers**: Defined in `.mcp.json` and `deps.edn`. Key servers include `mcp-shadow-dual` for REPL access and `dev_diagnostics` for environment checks.

## Common Workflows
- **Adding Features**: Follow a REPL-driven workflow. Write code in `src/`, add tests in `test/`, and use the REPL to interactively build.
- **Running Research**: Use the Research Skill (`skills/research/run.sh`) or Babashka tasks like `best-repos-research`.
- **Debugging Issues**: Use the REPL Debug Skill (`skills/repl-debug/`) for guidance and browser console helpers in `dev/debug.cljs`.
- **Checking Environment Health**: Run `dev/bin/agent-env-check.sh` or the Diagnostics Skill.

## Key Files Reference
- **`deps.edn`**: Clojure dependencies and aliases for running nREPL (`:nrepl`) and MCP servers (`:mcp`).
- **`shadow-cljs.edn`**: ClojureScript build configuration, dependencies, and nREPL port for CLJS tooling.
- **`package.json`**: Node.js dependencies and the main entry point for running development scripts.
- **`bb.edn`**: Babashka task runner configuration for complex, automated workflows.
- **`.mcp.json`**: Defines local and remote MCP servers for Claude Code agent integration.
- **`dev/tooling-index.edn`**: A central catalog of all development tools, their purposes, and how to invoke them.
- **`CLAUDE.md`**: High-level guide for AI agents on how to interact with the project.