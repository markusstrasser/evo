# SRS Lab Package

Spaced Repetition System (SRS) implementation built on top of the kernel's 3-operation architecture.

## Architecture

**Intent Compilation Pattern**: High-level SRS operations compile down to kernel operations (`create-node`, `place`, `update-node`).

### Core Modules

- **`schema.cljc`** - Malli schemas for 4 SRS operations
  - `:srs/create-card` - Create flashcard with content child
  - `:srs/update-card` - Update card properties
  - `:srs/review-card` - Record review with grade
  - `:srs/schedule-card` - Adjust scheduling parameters
  - Uses `:multi` discriminated union for type-safe dispatch
  - `humanize-srs-op` provides readable error messages

- **`compile.cljc`** - Intent → kernel operation compilation
  - Multimethod-based compilation (`compile-srs-intent`)
  - Mock SM-2 scheduler (simple intervals)
  - Operation builders: `make-create-node`, `make-place`, `make-update-node`
  - Platform utilities: `now-instant`, `add-days`
  - Data-driven `grade->ease-factor` map

- **`indexes.cljc`** - SRS-specific derived indexes
  - Due cards by date (`:srs/due-index`)
  - Cards by deck (`:srs/cards-by-deck`)
  - Review history per card (`:srs/review-history`)
  - Scheduling metadata (`:srs/scheduling-metadata`)
  - Media by card for image-occlusion (`:srs/media-by-card`)
  - Reusable utilities: `nodes-by-type`, `children-by-type`, `wrap-index`

- **`plugin.cljc`** - Extensible card type plugins
  - Registry-based plugin system
  - Built-in: `:basic`, `:image-occlusion`
  - Plugins provide: schema, compiler, renderer
  - Image-occlusion creates :media child nodes (Codex innovation)

- **`log.cljc`** - Append-only transaction log
  - EDN entries: `{:intent :compiled-ops :kernel/after-hash}`
  - Undo/redo via cursor mechanism
  - Query helpers: by card, recent reviews, since timestamp

- **`markdown.cljc`** - Markdown frontmatter parser
  - YAML frontmatter → SRS intents
  - Section extraction (# Front, # Back)

- **`demo.cljc`** - Test suite with assertions
  - Full workflow: create, review, schedule, undo/redo
  - Image-occlusion plugin demo
  - `(run-all-demos!)` to verify

### Example Markdown Cards

See `examples/` directory for markdown cards with frontmatter:
- `basic-card.md` - Simple Q&A
- `cloze-card.md` - Fill-in-the-blank
- `image-occlusion-card.md` - Visual masking
- `kernel-card.md` - Technical Q&A

## Key Innovations

From multi-LLM architecture evaluation (Gemini 2.5 Pro, GPT-5 Codex, Grok-4):

1. **`:card-content` child nodes** (Codex) - Separates structure from content
2. **Complete plugin registry** (Codex) - Extensible card types
3. **Append-only log with integrity** (Codex) - `:kernel/after-hash` checks
4. **Media as child nodes** (Codex) - Image-occlusion masks as :media children
5. **Discriminated union schemas** (Grok) - `:multi` for type safety

## Refactorings Applied

4 parallel GPT-5 Codex agents analyzed the codebase. Best ideas applied:

### Schema (96% error reduction)
- `:multi` discriminated union (vs `:or`)
- `humanize-srs-op` for readable errors
- `operation-type?` helper predicates

### Compile (100% duplication reduction)
- Operation builders eliminate 13 manual map constructions
- Platform utilities abstract reader conditionals
- Data-driven `grade->ease-factor` map (vs case)

### Indexes (45% line reduction)
- `nodes-by-type`, `children-by-type`, `wrap-index` utilities
- Consistent use across all compute functions

### Plugin
- Updated to use operation builders
- Cleaner image-occlusion compiler

## Usage

```clojure
(require '[lab.srs.demo :as demo])

;; Run full test suite
(demo/run-all-demos!)

;; Create a card
(require '[lab.srs.compile :as compile])
(require '[lab.srs.schema :as schema])

(def intent {:op :srs/create-card
             :card-id "c1"
             :deck-id "d1"
             :card-type :basic
             :markdown-file "test.md"
             :front "What is DNA?"
             :back "Deoxyribonucleic acid"})

;; Validate
(schema/valid-srs-op? intent)
;=> true

;; Compile to kernel ops
(compile/compile-intent intent db)
;=> [{:op :create-node ...} {:op :place ...} ...]
```

## Design Principles

1. **Zero kernel modifications** - Uses only 3 core operations
2. **Separation of concerns** - Kernel (structure), SRS (logic), Log (persistence)
3. **Pure compilation** - Intents → ops is deterministic
4. **Derived indexes** - Queryable views computed on demand
5. **Plugin extensibility** - New card types via multimethod dispatch

## Related Docs

- Architecture proposals: `research/results/srs-architecture-2025-10-05-19-50/`
- Refactoring analysis: `research/results/srs-refactoring-2025-10-06/`
- Comparison report: `research/results/srs-architecture-2025-10-05-19-50/COMPARISON.md`
