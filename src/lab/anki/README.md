# Local-First Anki Clone

A local-first spaced repetition system (SRS) built with ClojureScript, using the FSRS algorithm for optimal review scheduling.

## Features

- **Local-first**: Your card data stays on your device using the File System Access API
- **Event sourcing**: Immutable event log tracks all reviews, with undo/redo support
- **FSRS scheduling**: 19-parameter optimized algorithm for spaced repetition
- **Multiple card types**: Q&A, cloze deletions, image occlusions
- **Markdown storage**: Cards stored as plain `.md` files (ground truth)
- **Deck organization**: Organize cards into decks using folder structure

## Architecture

### Core Components

- `core.cljc` - Pure data transformations, event sourcing, card scheduling
- `fsrs.cljc` - FSRS (Free Spaced Repetition Scheduler) algorithm implementation
- `ui.cljs` - Replicant UI, event handlers, keyboard shortcuts
- `fs.cljs` - File System Access API wrappers, IndexedDB handle persistence
- `occlusion_creator.cljs` - Canvas-based image occlusion creation tool

### Data Flow

```
Markdown Files (Ground Truth)
      ↓
  Parse Cards
      ↓
Event Log (Metadata) → Reduce Events → App State
      ↓                                     ↓
 IndexedDB (Persist)                    UI Render
```

### Event Sourcing

All actions create immutable events:
- `:card-created` - New card added to deck
- `:review` - Card reviewed with rating (:forgot, :hard, :good, :easy)
- `:undo` - Mark event as undone
- `:redo` - Reactivate undone event

Events stored in `log.edn`, cards in `*.md` files.

## Card Types

### Q&A
```markdown
q What is the capital of France?
a Paris
```

### Cloze Deletion
```markdown
c The [mitochondria] is the [powerhouse] of the cell
```

### Image Occlusion
Created via the built-in canvas tool. Stored as EDN in `Occlusions.md`:
```clojure
{:type :image-occlusion/item
 :asset {:url "..." :width 800 :height 600}
 :prompt "What is this structure?"
 :occlusions [{:oid #uuid "..." :shape {:x 0.2 :y 0.3 :w 0.1 :h 0.1} :answer "Aorta"}]
 :current-oid #uuid "..."
 :mode :hide-one-guess-one}
```

## FSRS Algorithm

The Free Spaced Repetition Scheduler calculates optimal review intervals based on:
- **Stability**: How long a memory lasts
- **Difficulty**: How hard the card is (1.0 to 10.0)
- **Retrievability**: Probability of recall at review time

Uses 19 optimized weights to predict when you'll forget a card and schedules the next review just before that point.

## Performance

- File loading: ~5ms for 8 cards
- Event log loading: ~1.4s (scales with review history)
- Review processing: <50ms per card

## Keyboard Shortcuts

- **Space**: Show answer
- **1-4**: Rate card (Forgot, Hard, Good, Easy)
- **u**: Undo last review
- **r**: Redo undone review

## Development

See parent `CLAUDE.md` for:
- REPL workflow (`dev/debug.cljs` - browser console helpers)
- Testing (`npm test`)
- Build system (shadow-cljs)

## Testing

Run tests:
```bash
npm test
```

125 tests covering:
- Card parsing (Q&A, cloze, image occlusion)
- Event sourcing (event reduction, undo/redo)
- FSRS scheduling (intervals, stability, difficulty)
- Data integrity checks

## Browser Compatibility

Requires the File System Access API, currently supported in:

- Chrome/Edge 86+
- Opera 72+
- Safari (partial support)

Not supported in Firefox (as of 2025).

## Design Philosophy

This codebase follows **ruthless simplicity**:

- **No abstractions until pain is real**: We rejected 12 refactoring suggestions because the current 3 card types don't justify abstraction layers
- **Correctness over cleverness**: Fixed 3 subtle bugs (double-rendering, IndexedDB race, flatten misuse) that worked but were wrong
- **DRY when obvious**: Extracted `idb-request->promise` helper only after seeing the exact same pattern 3 times
- **Idiomatic over manual**: Use `medley/filter-vals` instead of `(map first (filter ...))` - but only when it's clearer
- **Separate async from pure**: Keep promise chains short by moving pure transformations into plain `let` blocks

### Code Quality Gates

- **4 parallel agent analysis** (GPT-5 Codex, high reasoning): Different perspectives catch different issues
- **Critical evaluation**: Rejected 63% of suggestions (12/19) as over-engineering
- **Test after every change**: 125 tests, 594 assertions must pass
- **Lint clean**: No new warnings introduced

### When to Refactor

✅ **Refactor when:**
- Correctness bugs (double-rendering, race conditions, data structure bugs)
- Same code duplicated 3+ times with no variation
- Idiomatic library function exists and is clearer
- Promise chains mix async I/O with pure transformations

❌ **Don't refactor when:**
- "Might need it later" (YAGNI)
- Abstraction for 3 items (wait for 5+)
- Micro-optimizations that hurt readability
- Trying to be "clever" instead of clear

### Refactoring Process

1. **Run parallel agents** with different scopes (per-file + cross-cutting)
2. **Critical analysis**: Evaluate each suggestion against status quo
3. **Estimate impact**: LOC change, correctness improvement, clarity gain
4. **Apply in priority order**: Correctness bugs first, then code quality
5. **Test after each change**: Compile, test, lint
6. **Commit granularly**: One logical change per commit

See `.architect/results/anki-refactor-*` for analysis history.
