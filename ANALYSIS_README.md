# EVO Project Analysis - Complete Documentation

This directory contains comprehensive analysis of the EVO outliner/editor project structure and implementation.

## Documents

### 1. **EVO_ARCHITECTURE_ANALYSIS.md** (713 lines)
Complete architectural deep-dive covering:
- Project overview and technology stack
- Detailed directory structure with descriptions
- Core architecture: three-op kernel, database shape, intent system, transaction pipeline
- Feature implementation mapping for all spec items:
  - Block movement (Cmd+Shift+Up/Down)
  - Indent/outdent (Tab/Shift+Tab)
  - Enter to split blocks
  - Backspace to merge blocks
  - Multi-selection operations
  - Drag-and-drop (partial)
  - Collapse/expand and zoom
  - Caret positioning behavior
  - Text formatting (bold, italic)
- Session state management (ephemeral vs. persistent)
- Complete keyboard shortcuts reference
- Entry points for UI and kernel
- Architecture diagrams
- Query layer documentation
- Testing notes and limitations
- Developer workflow and REPL debugging
- Feature completeness table (19/20 = 95%)

**Use this for**: Understanding the complete system architecture, how features work internally, following code traces end-to-end

### 2. **EVO_FEATURE_MAPPING.md** (323 lines)
Quick reference guide comparing your specification against EVO implementation:
- Your spec vs. EVO implementation for each feature
- Implementation details and code locations
- Complete feature inventory table
- Architecture quality observations (strengths and growth areas)
- How to use this analysis for implementation/testing/learning
- Key files to study (must-read and nice-to-have)
- Command reference for REPL and hot reload
- Executive summary

**Use this for**: Quick lookup, feature status checking, understanding what's implemented vs. what needs work

## Project Overview

**EVO** is an event-sourced UI kernel with a three-operation foundation:
- `create-node` - Add node to graph
- `place` - Position node in hierarchy
- `update-node` - Merge node properties

All user actions (indent, delete, move, format, etc.) compile into these three operations through an intent system.

### Technology
- **Language**: ClojureScript (cljc for shared code)
- **Framework**: Replicant (React-like)
- **Architecture**: Pure functional, event sourcing
- **Build**: Shadow CLJS

## Quick Facts

| Aspect | Details |
|--------|---------|
| **Language** | ClojureScript |
| **Total LOC** | ~2,500 across kernel, plugins, UI |
| **Main Architecture** | Three-op kernel + plugin intent system |
| **Feature Coverage** | 19/20 spec items (95%) - drag-drop UI partial |
| **Test Suite** | Exists but empty (REPL-first development) |
| **Documentation** | Excellent inline comments, see VISION.md for philosophy |

## File Structure Reference

```
src/
├── kernel/           # Pure kernel (DB, ops, transactions) - 12 files
├── plugins/          # High-level intents (structural, editing, selection, folding) - 9 files
├── keymap/           # Keyboard system (centralized bindings) - 3 files
├── shell/            # UI adapters (Replicant components) - 2 files
├── components/       # React components (block, refs, embeds) - 5 files
├── parser/           # Text parsing (refs, embeds, page refs) - 3 files
└── utils/            # Utilities - 1 file
```

## How to Navigate

### If you want to understand:
1. **How selections work** → `plugins/selection.cljc`
2. **How indent/outdent works** → `plugins/struct.cljc` lines 28-47
3. **How undo/redo works** → `kernel/history.cljc`
4. **How keyboard shortcuts map** → `keymap/bindings_data.cljc`
5. **How blocks are structured** → `kernel/db.cljc`
6. **How transactions are validated** → `kernel/transaction.cljc`
7. **How the component tree works** → `components/block.cljs`
8. **How events dispatch** → `shell/blocks_ui.cljs` lines 84-238

### If you want to implement:
1. **New keyboard shortcut** → Add to `keymap/bindings_data.cljc`, handler already exists
2. **New structural operation** → Register intent in `plugins/struct.cljc`
3. **New edit behavior** → Add intent in `plugins/editing.cljc` or `plugins/smart_editing.cljc`
4. **New UI feature** → Add to `components/block.cljs` and dispatch intent
5. **Drag-drop UI** → Add handlers to `components/block.cljs`, dispatch `:move` intent from `plugins/struct.cljc`

## Key Architectural Patterns

### Intent System
```clojure
;; 1. User action (click, keystroke)
;; 2. Resolve to intent: {:type :indent :id "block-1"}
;; 3. Kernel compiles to ops: [{:op :place :id "block-1" :under prev-id :at :last}]
;; 4. Transaction pipeline: normalize → validate → apply → derive
;; 5. DB updated, history recorded
;; 6. Component re-renders with new selection/styles/content
```

### Three-Op Decomposition
Every feature (delete, indent, split, move, etc.) reduces to these three operations. This enables:
- Auditable change log (all ops are EDN data)
- Undo/redo (transaction replay)
- Testing (pure functions)
- AI integration (ops as composable building blocks)

### Plugin System
Plugins register intents that compile to ops:
```clojure
(register-intent! :indent
  {:doc "Move block under previous sibling"
   :spec [:map [:type [:= :indent]] [:id :string]]
   :handler (fn [db {:keys [id]}] [ops...])})
```

No global state, no side effects, all pure transformations.

## Learning Path

**Recommended reading order**:
1. README.md (project philosophy)
2. VISION.md (architectural thinking)
3. kernel/ops.cljc (the three operations)
4. kernel/intent.cljc (how to add features)
5. kernel/transaction.cljc (the pipeline)
6. plugins/struct.cljc (example: complex plugin)
7. shell/blocks_ui.cljs (example: event dispatch)
8. components/block.cljs (example: UI integration)

**Then explore**:
- EVO_ARCHITECTURE_ANALYSIS.md for specific features
- EVO_FEATURE_MAPPING.md for quick lookups
- Source files with specific questions

## Key Insights

### What Makes EVO Special

1. **Kernel/Shell Separation**: All logic in pure functions, UI adapters are thin
2. **Intent Routing**: Single dispatcher, no scattered handlers
3. **Transaction Safety**: Validation prevents invalid states
4. **Ephemeral UI State**: Fold/zoom/editing not in history (good UX)
5. **Anchor Algebra**: Deterministic positioning (no off-by-one bugs)

### What's Missing

1. **Drag-drop UI**: Core logic ready, needs DOM handlers + visual guides
2. **Performance**: Not optimized for 10k+ blocks (would need incremental derivation)
3. **Collaboration**: No multi-user sync (but architecture supports it)
4. **Tests**: Property-based suite is empty (codebase is test-ready though)

## File Sizes

- EVO_ARCHITECTURE_ANALYSIS.md: 713 lines (comprehensive reference)
- EVO_FEATURE_MAPPING.md: 323 lines (quick lookup)
- Total: ~1,000 lines of analysis

## Questions This Analysis Answers

- What language/framework is EVO? → ClojureScript + Replicant
- How is block movement implemented? → Multi-selection + `:move-selected-up` intent + anchor algebra
- How does indent work? → `:place` op under prev sibling
- How is multi-selection stored? → `session/selection` node with `{:nodes #{} :focus id :anchor id}`
- How are keyboard shortcuts centralized? → keymap/bindings_data.cljc (declarative data)
- How does undo/redo work? → kernel/history.cljc (transaction recording)
- What's the transaction pipeline? → normalize → validate → apply → derive
- How are new features added? → Register intent in plugin, add keymap binding
- What operations are possible? → All operations decompose to create/place/update
- Is drag-drop implemented? → Core logic yes (60%), UI handlers no (40%)

## Next Steps

1. Read **EVO_ARCHITECTURE_ANALYSIS.md** for complete understanding
2. Use **EVO_FEATURE_MAPPING.md** as reference while exploring code
3. Start with `kernel/ops.cljc` to understand the foundation
4. Pick a feature and trace it end-to-end (keymap → intent → ops → db)
5. Try REPL debugging with the patterns in "Developer Workflow" section

## References

- **Project Repo**: /Users/alien/Projects/evo
- **Main Docs**: README.md, VISION.md, STYLE.md
- **Architecture Docs**: /docs/ directory
- **Source**: src/ with 12 subdirectories

---

Generated: November 9, 2025
Analyzed by Claude Code (Haiku 4.5)
