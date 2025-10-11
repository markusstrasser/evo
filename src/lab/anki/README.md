# Local-First Anki Clone

A local-first spaced repetition system built with ClojureScript and Replicant.

## Features

- **Local-first**: All data stored as real files on disk (git-friendly, editor-friendly)
- **Zero server dependencies**: Pure static site using File System Access API
- **Pure functional UI**: Replicant's data-driven approach
- **Event sourcing**: All actions stored as events, state derived by reduction
- **SM-2 inspired scheduling**: Simple but effective spaced repetition algorithm

## Architecture

### File Structure

When you select a folder, the app creates these files:

```
your-folder/
├── cards.md      # Human-readable card content
├── log.edn       # Append-only event log
└── meta.edn      # Card metadata and scheduling info
```

### Card Formats

**QA Cards** (question/answer):
```
What is the capital of France? ; Paris
What is 2+2? ; 4
```

**Cloze Deletion**:
```
Human DNA has [3 Billion] base pairs
The [mitochondria] is the [powerhouse] of the cell
```

### Data Model

**Event Log** (`log.edn`):
```clojure
[{:event/type :card-created
  :event/timestamp #inst "2025-10-07"
  :event/data {:card-hash "abc123"
               :card {:type :qa :question "Q" :answer "A"}}}
 {:event/type :review
  :event/timestamp #inst "2025-10-07"
  :event/data {:card-hash "abc123"
               :rating :good}}]
```

**State Shape** (derived from events):
```clojure
{:cards {"abc123" {:type :qa :question "Q" :answer "A"}}
 :meta {"abc123" {:card-hash "abc123"
                  :created-at #inst "2025-10-07"
                  :due-at #inst "2025-10-08"
                  :interval 1
                  :ease-factor 2.5
                  :reviews 1}}}
```

### Card Hashing

Cards are hashed based on their content. If you edit a card in `cards.md`, it becomes a new card with fresh review history. This ensures:

- Content changes are tracked
- Review history matches the actual content reviewed
- No orphaned metadata

### Scheduling Algorithm

**Mock algorithm for testing** - simple fixed intervals:

- **Forgot**: Review again immediately (0ms)
- **Hard**: Review in 1 minute (60000ms)
- **Good**: Review in 5 minutes (300000ms)
- **Easy**: Review in 10 minutes (600000ms)

All ratings increment the review count and store the last rating.

_Note: This is a placeholder. Replace with a real spaced repetition algorithm (SM-2, FSRS, etc.) later._

## Usage

### Core Functions

```clojure
(require '[lab.anki.core :as core])

;; Parse cards
(core/parse-card "What is 2+2? ; 4")
;; => {:type :qa :question "What is 2+2?" :answer "4"}

(core/parse-card "DNA has [3 billion] base pairs")
;; => {:type :cloze :template "DNA has [3 billion] base pairs" :deletions ["3 billion"]}

;; Create events
(core/card-created-event "hash123" {:type :qa :question "Q" :answer "A"})
(core/review-event "hash123" :good)

;; Reduce events to state
(core/reduce-events events)

;; Query
(core/due-cards state)
(core/card-with-meta state "hash123")
```

### File System Operations

```clojure
(require '[lab.anki.fs :as fs])
(require '[promesa.core :as p])

;; Pick directory
(p/let [handle (fs/pick-directory)]
  (fs/save-dir-handle handle))

;; Load/save data
(p/let [log (fs/load-log dir-handle)]
  (prn log))

(p/let [_ (fs/append-to-log dir-handle [event1 event2])]
  (prn "Saved!"))
```

### UI Integration

```clojure
(require '[lab.anki.ui :as ui])

;; Initialize app
(p/let [app-state (ui/init-app!)]
  ;; Render with Replicant
  (replicant/render [ui/main-app app-state]))
```

## Testing

The core logic is fully tested in `test/lab/anki/core_test.cljc`:

- Card parsing (QA and cloze)
- Card hashing (content-based)
- Scheduling algorithm
- Event creation and application
- State reduction
- Queries (due cards, card with meta)

Run tests:
```bash
npm test
```

## Browser Compatibility

Requires the File System Access API, currently supported in:

- Chrome/Edge 86+
- Opera 72+
- Safari (partial support)

Not supported in Firefox (as of 2025).

## Future Enhancements

- [ ] Undo/redo for reviews
- [ ] Multiple cloze deletions per card
- [ ] Card editing UI
- [ ] Statistics and progress tracking
- [ ] Multiple decks
- [ ] Import from Anki format
- [ ] Sync across devices (WebRTC/CRDT)

## Design Principles

1. **Pure functions**: All core logic is pure and testable
2. **Event sourcing**: State is derived, not mutated
3. **Local-first**: Files are the source of truth
4. **Human-readable**: All data formats are git-friendly
5. **Zero dependencies**: Works offline, no server needed
6. **Progressive enhancement**: Start simple, add features incrementally

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
- **Test after every change**: 103 tests, 374 assertions must pass
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

See `research/results/anki-refactor-round3-*/CRITICAL_ANALYSIS.md` for the latest analysis.
