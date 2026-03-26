# Evo Commit Conventions

Supplements global commit message format with evo-specific scopes.

## Scopes

Canonical scopes in `.git-scopes`. Common ones:

| Scope | Covers |
|-------|--------|
| kernel | src/kernel/ — db, ops, transaction, schema, query, errors |
| shell | src/shell/ — UI adapters, session state, executor, keyboard dispatch |
| ui | src/components/ — Replicant UI components |
| plugins | src/plugins/ — intent handlers |
| keymap | src/keymap/ — keybinding definitions |
| scripts | src/scripts/ — multi-step operation scripts |
| parser | src/parser/ — page ref parsing |
| tests | test/ — unit, property, integration tests |
| e2e | e2e/ — Playwright end-to-end tests |
| infra | Build config, MCP, CI, tooling |
| docs | docs/, CLAUDE.md, README |
| specs | resources/specs.edn, FR registry |

## Examples

```
[kernel] Guard :parent selection against container types
[ui] Use shared scroll-into-view! utility — replaces 4 raw .scrollIntoView calls
[shell] Use parsed keydown event map — eliminates redundant raw DOM reads
[tests] Update tests for journal ISO support and parent selection guard
```
