# Project Navigation Guide Prompt

Generate a concise, practical guide for navigating and working with this project. This is for AI agents/developers who need to quickly understand the project structure, tooling, and workflows.

## Goal
Create a terse reference that answers:
- **Where is X?** (file locations, conventions)
- **How do I Y?** (common workflows)
- **What's the dev workflow?** (REPL, testing, quality gates)
- **Where do I find context?** (research, docs, decisions)

## Input
The codebase **excludes** `src/`, `test/`, and research results. You're seeing:
- Configuration files: `deps.edn`, `package.json`, `shadow-cljs.edn`, `.clj-kondo/config.edn`
- Dev tooling: `dev/`, `scripts/`
- Documentation: `docs/`, `CLAUDE.md`, `README.md`, `STYLE.md`
- MCP integration: `mcp/`, `.mcp.json`

## Output Format

Use clear sections with headers:

```
# Project: {name}

## Quick Start
- How to get started (install, run, test)

## Project Structure
- Key directories and their purpose
- Important files

## Development Workflow
- REPL setup
- Testing strategy
- Quality gates (linting, pre-commit)
- Debugging tools

## Documentation & Context
- Where to find what
- Research materials location

## Tooling & Automation
- NPM scripts
- Shell scripts
- Git hooks
- MCP servers

## Common Workflows
- Adding features
- Running research
- Debugging issues

## Key Files Reference
- Configuration files with brief descriptions
```

## Style Guidelines
- Be concise - 2-3 lines per item
- Use bullet points
- Include file paths (e.g., `dev/repl/init.clj`)
- Mention key npm commands
- Reference important CLAUDE.md sections
- Skip implementation details
- Focus on "where" and "how", not "why"

## What to Include
- Directory structure with purposes
- Key configuration files
- NPM scripts (from package.json)
- Shell scripts (from scripts/)
- REPL workflow
- Testing commands
- Quality gates
- MCP server locations
- Documentation structure
- Research workflow (if applicable)

## What to Exclude
- Source code details (that's for AUTO-SOURCE-OVERVIEW.md)
- Implementation logic
- Test code
- Detailed architectural decisions
- Line-by-line explanations

## Purpose
This guide should enable someone to:
- Navigate the project quickly
- Run common commands without searching
- Know where to find specific types of files
- Understand the dev workflow
- Access documentation and research
