# Intent Router Extension Proposals

**Status:** Discussion (all deferred)
**Created:** 2025-10-24
**Related:** ADR-016 (Intent Router Architecture)

## Overview

Three non-breaking extensions to the intent router that enable advanced features. All are **deferred until needed** - the current architecture works well for simple single-user use.

These proposals document patterns learned from comparing our architecture to ProseMirror and Logseq.

## Proposals

### 01. Transaction Metadata
**Problem:** No way to attach context to operations (undo boundaries, source tracking)
**Solution:** Add optional `meta` param that flows through pipeline
**Effort:** ~30 lines
**When:** Undo/redo or collaborative editing

### 02. Plugin Hooks
**Problem:** Plugins can't validate, observe, or transform intents
**Solution:** Two multimethods: `before-apply-intent` and `after-apply-intent`
**Effort:** ~50 lines
**When:** Access control, audit logging, or intent chaining

### 03. Command Composition
**Problem:** Complex keymaps need fallback chains (Tab tries 3+ things)
**Solution:** `chain-intents` helper that tries intents until one succeeds
**Effort:** ~15 lines
**When:** Autocomplete, context-aware keys (Tab/Enter/Backspace)

## Implementation Order (if needed)

1. **Command Composition** - Simplest, useful for keymaps
2. **Transaction Metadata** - Needed for undo/redo
3. **Plugin Hooks** - Most complex, only if plugin ecosystem emerges

All three are **additive** - no refactoring required.

## Architecture Compatibility

✅ All extensions are **non-breaking** additions
✅ Can be added **incrementally** (one at a time)
✅ No changes to 3-op kernel or dual multimethod pattern
✅ Pure extensions, not refactors

## Comparison: ProseMirror vs Logseq

| Feature | ProseMirror | Logseq | Evo (Current) | Evo (Proposed) |
|---------|-------------|--------|---------------|----------------|
| Metadata | `tr.setMeta()` | DataScript tx-meta | ❌ None | ✅ Optional meta |
| Hooks | `filterTransaction` | Event handlers | ❌ None | ✅ before/after hooks |
| Composition | `chainCommands()` | Manual conditionals | ❌ None | ✅ chain-intents |

Our proposed extensions are **simpler** than both ProseMirror and Logseq while covering the same use cases.

## Decision

**All three proposals are deferred.** The current intent router (ADR-016) works well for:
- Solo developer
- Single user (no collaboration)
- Simple keymaps (one intent per key)
- No undo/redo yet

Add extensions **incrementally when use cases emerge**.

## Usage: When to Revisit

### Transaction Metadata
- [ ] Implementing undo/redo system
- [ ] Adding collaborative editing
- [ ] Need operation source tracking for debugging

### Plugin Hooks
- [ ] Complex validation rules (permissions, business logic)
- [ ] Audit logging for compliance
- [ ] Intent transformation (auto-expand parent, etc.)

### Command Composition
- [ ] Adding autocomplete/suggestions
- [ ] Context-aware Tab key (3+ fallbacks)
- [ ] Smart Enter/Backspace behavior

## Related Files

- `src/core/intent.cljc` - Current intent router implementation
- `.architect/adr/ADR-016-split-api-structural-vs-annotations.md` - Architecture decision
- `docs/comparisons/prosemirror-logseq-comparison.md` - Full architecture comparison
