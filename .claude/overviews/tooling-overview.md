<!-- Generated: 2026-03-28T15:18:08Z | git: 49905601 | model: gemini-3-flash-preview -->

<!-- INDEX
[HOOK] PreToolUse — Guards specs.edn and golden fixtures during Write/Edit, blocks unauthorized changes
[HOOK] PostToolUse — Checks for documentation staleness after Bash commands, advises on propagation
[HOOK] pre-commit — Quality checks (shadowing, E2E keyboard, namespace consistency) before commit
[HOOK] pre-push — Triggers automated overview generation before pushing to remote
[SKILL] cljs-ui-debugging — Expert guidance for debugging ClojureScript UI, focus, and Replicant lifecycles
[AGENT] researcher — Deep research subagent for synthesizing technical findings from multiple sources
[AUTOMATION] generate-overview.sh — AI-powered architectural documentation generator (source, project, dev)
[AUTOMATION] audit_ci.clj — CI ratchet check ensuring 100% critical FR coverage
[AUTOMATION] install-hooks.sh — Utility to deploy project git hooks to .git/hooks
[CONFIG] .claude/settings.json — Configures Claude Code hooks and tool guards
[CONFIG] .claude/rules/invariants.md — Defines architectural "laws" (e.g., Kernel Purity) and quality gates
[CONFIG] bin/keychain-load-env.sh — Loads API secrets from macOS Keychain into the shell environment
-->

### Claude Code Integration

The project uses `.claude/` for agent orchestration and safety rails.

#### Hooks Configuration (`settings.json`)

| Hook | Matcher | Script | Purpose | Type |
| :--- | :--- | :--- | :--- | :--- |
| **PreToolUse** | `Write\|Edit` | `pretool-data-guard.sh`* | Guards `specs.edn` and golden fixtures | Block |
| **PostToolUse** | `Bash` | `postcommit-doc-staleness.sh`* | Checks for documentation propagation | Advise |

*\* Referenced in config but resides in external `~/Projects/skills/hooks/` directory.*

#### Agent Resources
*   **[SKILL] cljs-ui-debugging**: Located in `.claude/skills/cljs-ui-debugging/`. Provides expert procedural guidance for debugging focus, cursor, and Replicant lifecycle issues.
*   **[AGENT] researcher**: Defined in `.claude/agents/researcher.md`. A persistent subagent configuration for multi-source technical investigation (Context7, Exa, Web).
*   **[CONFIG] .claude/rules/invariants.md**: Enforces strict architectural constraints, including "Kernel Purity" (no UI deps in kernel) and extraction-mode priorities.
*   **[CONFIG] .claude/rules/commit-conventions.md**: Defines project-specific scopes (`kernel`, `shell`, `ui`, `plugins`) for standardized commit messages.

### [AUTOMATION] generate-overview.sh
An AI-powered documentation engine that uses `repomix` and Gemini to maintain architectural overviews.
*   **Trigger**: Manual or via `pre-push` hook.
*   **Modes**: `--source` (code logic), `--project` (structure), and `--dev` (tooling).
*   **Configuration**: Managed via `.claude/overview.conf`.

### [AUTOMATION] audit_ci.clj
A Babashka-based CI/CD ratchet check located in `scripts/audit_ci.clj`.
*   **Purpose**: Prevents regression by failing the build if any "CRITICAL" Functional Requirement (FR) loses its intent coverage.
*   **Related**: `gen_coverage.clj` generates the visual `FR_MATRIX.md` report.

### [AUTOMATION] install-hooks.sh
A utility script in `scripts/` to deploy the project's specialized git hooks.
*   **[HOOK] pre-commit**: Runs `lint-e2e-keyboard.js` (Playwright anti-patterns), checks for shadowed Clojure core variables, and validates namespace/path consistency.
*   **[HOOK] pre-push**: Ensures `generate-overview.sh --auto` runs before code leaves the local environment.

### Project Configuration

Notable environment and tool settings:

*   **[CONFIG] bin/keychain-load-env.sh**: A security utility to load API keys (OpenAI, Gemini, Anthropic, etc.) from the macOS Keychain instead of plaintext `.env` files.
*   **[CONFIG] .claude/overview.conf**: Centralizes parameters for AI overview generation, including model selection (`gemini-3-flash-preview`) and path exclusions.
*   **Quality Analysis**:
    *   `scripts/complexity.clj`: Unified function and namespace complexity analyzer.
    *   `scripts/call-graph.clj`: Static analysis tool for identifying system entry points and hot paths.
    *   `scripts/lint_specs.clj`: Fast validation for the `resources/specs.edn` FR registry.
*   **Development Utilities**:
    *   `scripts/watch-runner.mjs`: Node wrapper for `shadow-cljs` that performs cache health checks before starting the dev server.
    *   `scripts/scaffold-view-test.clj`: Automatically generates Hiccup-based rendering tests for TitleCase UI components.
