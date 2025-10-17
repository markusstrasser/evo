# Dev Tooling & Infrastructure

A concise reference for using the project's development tools, REPL helpers, and automation scripts.

## Overview
- **`dev/`**: Contains tools for REPL-driven development, testing, and health checks.
- **`bb/` (Babashka)**: Holds standalone scripts for automation, research, and infrastructure tasks.
- **Philosophy**: REPL-first, testable, and composable. Utilities are designed to be small, focused, and reusable.

## REPL Utilities (`dev/repl/`)
- **Init REPL**: Use `(require '[repl :as repl])` and `(repl/init!)` to load core namespaces.
- **Helpers (`dev/repl/init.clj`)**:
  - `(repl/connect!)`: Connect to the shadow-cljs build.
  - `(repl/cljs! '(js/alert "Hi"))`: Evaluate code in the browser.
  - `(repl/rt! 'my.ns-test)`: Run tests for a specific namespace.
- **Session Management (`dev/repl/session.clj`)**:
  - `(save-session!)`: Saves loaded namespaces and current ns to `dev/.repl-session.edn`.
  - `(restore-session!)`: Reloads namespaces from the saved session file.
- **Scenarios**: Find copy-pasteable examples in `dev/repl/lens_scenarios.clj` and `dev/repl/malli_patterns_validation.clj`.

## Test Support
- **Generic Fixtures (`dev/fixtures.cljc`)**:
  - **Tree Builders**: `(fix/make-db {...})`, `(fix/gen-linear-tree 5)`, `(fix/gen-balanced-tree 3 2)`.
  - **Predefined Data**: `fix/empty-db`, `fix/simple-tree`.
- **Application Fixtures (`dev/anki_fixtures.cljc`)**:
  - **Scenario Generators**: `(anki-fix/review-session-fixture ...)` for creating specific Anki test states.
  - **State Inspectors**: `(anki-fix/print-state-summary state)`.

## Health & Diagnostics
- **REPL Checks (`dev/health.clj`)**:
  - `(h/preflight-check!)`: Checks for process conflicts and stale caches.
  - `(h/cache-stats)`: Reports sizes of `.shadow-cljs`, `out`, etc.
  - `(h/clear-caches!)`: Nukes caches to resolve corruption.
- **Shell Scripts (`dev/bin/`)**:
  - `dev/bin/health-check.sh`: Quick check for running servers and compilation.
  - `dev/bin/preflight.sh`: Validates the environment based on `dev/preflight.edn`.
  - `dev/bin/validate-environment.sh`: Systematically checks for required tools (node, clj, etc.).
- **Error Catalog (`dev/error-catalog.edn`)**: A knowledge base of common errors and their remedies.

## Babashka Utilities (`dev/scripts/utils/`)
These are reusable modules for Babashka scripts.
- **`files.clj`**: File I/O helpers (`read-file`, `write-file`, `list-files`).
- **`shell.clj`**: Run shell commands with mocking support (`sh`, `sh!`).
- **`http.clj`**: HTTP client with mocking support (`request`, `get-json`).
- **`json.clj`**: JSON/EDN parsing and generation utilities.

## Babashka Scripts (`dev/scripts/research/`)
Standalone scripts for automation and research, typically run via `bb`.
- **Examples**:
  - `dev/scripts/research/architectural_proposals.clj`: Generates and ranks architectural proposals from LLMs.
  - `dev/scripts/research/best_repos_research.clj`: Analyzes external repositories for patterns.
- **Execution**: Run scripts from the command line, e.g., `bb dev/scripts/research/analyze_battle_test.clj`.

## Configuration
- **`dev/config.edn`**: Central configuration for paths, ports, timeouts, and build settings.
- **`dev/preflight.edn`**: Defines checks used by the `dev/bin/preflight.sh` script.
- **`bb.edn`**: Configures tasks and dependencies for Babashka scripts.
- **`tooling-index.edn`**: A catalog of all dev tools, MCPs, skills, and scripts available in the project.

## Testing Strategy
- **REPL-Driven**: Most testing is done interactively from the REPL using fixtures.
- **Mocking**: `shell.clj` and `http.clj` provide `enable-mocking!` functions to test scripts without external dependencies.
- **Robust Runners**: For CI/automation, use `dev/bin/robust-test-runner.sh` which validates the environment before running tests. `dev/bin/test-with-output.sh` captures full results to a file for agents to read.

## Common Workflows
- **Starting REPL**: Run `npm run dev`, then connect your editor to the nREPL port specified in `dev/config.edn`.
- **Running Health Checks**: Execute `dev/bin/health-check.sh` or run `(h/preflight-check!)` in the REPL.
- **Using Test Fixtures**: `(require '[fixtures :as fix])` and `(def db (:db fix/simple-tree))`.
- **Creating a New Babashka Script**: Create a new `.clj` file and use the utilities from `dev/scripts/utils/` by requiring them.
