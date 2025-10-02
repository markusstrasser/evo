# Dev Tooling & Infrastructure Guide Prompt

Generate a concise, practical guide for the project's development tooling and infrastructure. This is for AI agents/developers who need to quickly understand and use the dev tools, REPL helpers, and automation scripts.

## Goal
Create a terse reference that answers:
- **What dev tools exist?** (REPL helpers, fixtures, health checks)
- **How do I use them?** (REPL workflows, testing utilities)
- **What utilities are available?** (Babashka modules, shared libraries)
- **Where is X?** (file locations in dev/ and bb/)

## Input
The codebase shows `dev/` and `bb/` directories containing:
- REPL utilities: `dev/repl/` (init.clj, session.clj, scenarios)
- Test support: `dev/fixtures.cljc` (test data generators)
- Health checks: `dev/health.clj` (diagnostics, cache management)
- Configuration: `dev/config.edn`, `dev/preflight.edn`
- Babashka utilities: `bb/*.clj` (json, shell, http, files modules)
- Babashka scripts: `bb/*.clj` (converted infrastructure scripts)
- Shell scripts: `dev/bin/*.sh` (environment checks, validation)

## Output Format

Use clear sections with headers:

```
# Dev Tooling & Infrastructure

## Overview
- Purpose of dev/ and bb/ directories
- Philosophy: REPL-first, testable, composable

## REPL Utilities (dev/repl/)
- How to init REPL
- Available helper functions
- Scenario examples

## Test Support (dev/fixtures.cljc)
- Test data generators
- Tree builders
- Predefined fixtures

## Health & Diagnostics (dev/health.clj)
- Environment checks
- Cache management
- Debugging utilities

## Babashka Utilities (bb/)
- json.clj - JSON/EDN parsing
- shell.clj - Shell commands with mocking
- http.clj - HTTP client with mocking
- files.clj - File I/O utilities
- How utilities are structured for reuse

## Babashka Scripts (bb/)
- Converted infrastructure scripts
- How to run them (bb <task-name>)
- Available tasks (from bb.edn)

## Configuration
- dev/config.edn - Dev environment config
- dev/preflight.edn - Pre-flight check definitions
- bb.edn - Babashka task configuration

## Testing Strategy
- How dev tools are tested
- Mocking infrastructure
- REPL-driven development

## Common Workflows
- Starting REPL
- Running health checks
- Using test fixtures
- Creating new Babashka scripts
```

## Style Guidelines
- Be concise - 2-3 lines per item
- Use bullet points
- Include file paths (e.g., `dev/repl/init.clj`)
- Show example function calls
- Reference key namespaces
- Skip implementation details
- Focus on "what" and "how to use"

## What to Include
- Directory structure (`dev/`, `bb/`)
- Key files in each directory
- REPL helper functions
- Test fixture generators
- Health check commands
- Babashka utility modules
- Available bb tasks
- Configuration files
- Mocking infrastructure
- Common workflows

## What to Exclude
- Source code implementation (that's for AUTO-SOURCE-OVERVIEW.md)
- Project structure (that's for AUTO-PROJECT-OVERVIEW.md)
- Detailed architectural decisions
- Line-by-line code explanations

## Purpose
This guide should enable someone to:
- Set up and use the REPL efficiently
- Generate test data quickly
- Run health checks and diagnostics
- Use Babashka utilities in scripts
- Understand the dev tooling philosophy
- Create new dev utilities following established patterns
