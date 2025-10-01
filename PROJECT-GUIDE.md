# Project Navigation Guide Prompt

Generate a concise, practical guide for navigating and working with this project. This is for AI agents/developers who need to quickly understand the project structure, tooling, and workflows.

## Goal
Create a terse reference that answers:
- **Where is X?** (file locations, conventions)
- **How do I Y?** (common workflows)
- **What's the dev workflow?** (REPL, testing, quality gates)
- **Where do I find context?** (research, docs, decisions)

## Input
Scan the **project root** (exclude `src/`, `test/`, `out/`, `node_modules/`, `.git/`):
- Configuration files: `deps.edn`, `package.json`, `shadow-cljs.edn`, `.clj-kondo/config.edn`
- Dev tooling: `dev/`, `scripts/`
- Documentation: `docs/`, `CLAUDE.md`, `README.md`, `STYLE.md`
- Research: `research/`, `docs/research/`
- MCP integration: `mcp/`, `.mcp.json`

## Output Format

### Structure
```
PROJECT GUIDE
═════════════════════════════════════════════════════════════════════════════

1. QUICK START
   → How to get oriented immediately
   → Essential commands
   → Where to look first

2. PROJECT STRUCTURE
   → Directory tree with 1-line descriptions
   → File naming conventions
   → What lives where

3. DEVELOPMENT WORKFLOW
   → REPL setup and helpers
   → Testing (npm run test, dev tools)
   → Quality gates (linting, pre-commit)
   → Debugging (error-catalog.edn, health checks)

4. DOCUMENTATION & CONTEXT
   → CLAUDE.md - Agent instructions
   → docs/research/ - Research findings (structured: 00-context, 10-research, 20-proposals, etc.)
   → research/ - Research automation metadata (CLI_REFERENCE, ARCHITECTURAL_PROPOSALS)
   → STYLE.md - Code style guide
   → docs/overviews/ - Historical overviews

5. TOOLING & AUTOMATION
   → NPM scripts (package.json)
   → Dev scripts (dev/bin/, scripts/)
   → Git hooks (post-merge overview generation)
   → MCP servers (mcp/servers/)

6. COMMON WORKFLOWS
   → Adding a feature
   → Running research queries
   → Generating proposals
   → Debugging issues

7. KEY FILES REFERENCE
   → deps.edn - Clojure dependencies, aliases
   → shadow-cljs.edn - CLJS build config
   → dev/config.edn - Dev configuration
   → dev/error-catalog.edn - Known error patterns

═════════════════════════════════════════════════════════════════════════════
```

## Style Guidelines
- **Terse and scannable** - Use bullet points, not paragraphs
- **File paths** - Always use backticks: `dev/repl/init.clj`
- **Commands** - Show exact syntax: `npm run test`
- **Structure over prose** - Prefer trees, lists, tables
- **One-liners** - Each item gets max 1 line description
- **No fluff** - Skip "Introduction", "Conclusion", "As you can see"

## Example Output Snippet
```
3. DEVELOPMENT WORKFLOW
─────────────────────────────────────────────────────────────────────────────

REPL Setup:
  • Start: (require '[repl :as repl]) (repl/init!)
  • Location: dev/repl/init.clj (connect!, init!, cljs!, clj!)
  • Session mgmt: dev/repl/session.clj (save-session!, restore-session!)

Testing:
  • Run all: npm test
  • Run specific: (rq! 'namespace-test)
  • Location: test/*-test.cljc

Quality Gates:
  • Pre-commit: .pre-commit-check.sh (lint, test, namespace validation)
  • Lint only: npm run lint
  • Full check: npm run check (lint + compile)

Environment Health:
  • Quick check: npm run agent:health
  • REPL health: npm run repl:health
  • Error patterns: dev/error-catalog.edn
```

## What NOT to Include
- Source code implementation details (that's in AUTO-SOURCE-OVERVIEW.md)
- Low-level function signatures
- Implementation algorithms
- Detailed code examples (only command syntax)

## Output Requirements
- **Length**: 150-250 lines max
- **Format**: Plain markdown, no code fences for the entire document
- **Sections**: Exactly 7 sections as outlined above
- **Tone**: Reference manual, not tutorial
