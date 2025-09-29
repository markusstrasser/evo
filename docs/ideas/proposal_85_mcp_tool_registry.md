# Proposal 85 · MCP-Style Tool Registry for Kernel Extensions

## Problem
Extension points (deck rules, storyboards, intent plugins) each invent their own registration story. This scatters validation and result formatting logic.

## Inspiration
`clojure-mcp` centralises tool definition via multimethods (`tool-name`, `tool-schema`, `execute-tool`), and the default `registration-map` wires validation + execution in one place.cite/Users/alien/Projects/inspo-clones/clojure-mcp/src/clojure_mcp/tool_system.clj:1-120

## Proposed Change
1. Create `kernel.registry` with multimethods mirroring MCP:
   ```clojure
   (defmulti extension-name :type)
   (defmulti extension-schema :type)
   (defmulti execute-extension (fn [cfg _] (:type cfg)))
   ```
2. Provide `(register-extension cfg)` that validates inputs, executes the handler, and standardises responses (`{:result [...] :error? bool}`).
3. Use the registry for deck rules, sanity checks, and plugin-defined intents so tooling can enumerate capabilities consistently.

## Expected Benefits
- One place to enforce schemas, logging, and error shaping across all extension flavours.
- Simplifies LLM-facing metadata (the registry can spit out capability lists automatically).

## Trade-offs
- Multimethod dispatch adds a small perf cost; acceptable for extension code but worth measuring.
- Requires refactoring existing registries—should be staged carefully to avoid breaking adapters.

## Roll-out Steps
1. Implement the registry + default `registration-map` inspired by MCP, including uniform error reporting.
2. Migrate one extension type (e.g. deck rules) to prove the pattern.
3. Update documentation and CLI tooling to enumerate extensions from the unified registry.
