# Testing

## Commands

```bash
# Unit tests (ClojureScript via shadow-cljs)
bb test              # Compile + run full suite
bb test:view         # View-only tests (<1s)
bb test:int          # Integration tests
bb test-watch        # Watch entire suite
bb test-watch:view   # Watch view tier only

# Quality gates
bb check             # Lint + compile check
bb lint              # clj-kondo linter
```

---

## Testing Pyramid

Follow the [testing pyramid](https://kentcdodds.com/blog/static-vs-unit-vs-integration-vs-e2e-tests):

```
        ╱╲
       ╱E2E╲        ← Few: critical user journeys only (not yet implemented)
      ╱──────╲
     ╱ Integr ╲     ← Session-aware flows (kernel + session + effects)
    ╱──────────╲
   ╱   Unit     ╲   ← Many: pure functions, intent handlers, kernel ops
  ╱──────────────╲
```

### Current Test Distribution

| Layer | Count | Purpose |
|-------|-------|---------|
| Kernel/Core | ~30 | Transaction, schema, position, history |
| Plugins | ~130 | Intent handlers (isolated, pure) |
| Shell/Nexus | ~20 | Action purity, effect dispatch |
| Integration | ~20 | Multi-step flows with session |
| View | ~10 | Hiccup generation |
| Macros | ~25 | Multi-step scripts |

---

## Philosophy

Replicant's superpower: UI is pure data (hiccup). Test without a browser:
1. Call view functions with state
2. Inspect resulting hiccup
3. Extract actions from event handlers
4. Test handlers as pure functions

---

## Critical Gap: Buffer vs DB State

**Problem discovered**: Unit tests set DB state directly, but live editing uses a buffer:

```
User types → DOM contenteditable → session/buffer → (blur) → DB
```

Intent handlers that read `(get-block-text db block-id)` see **stale DB text** during editing.
The current text lives in the buffer until committed.

### How to Test This

**Session-aware integration tests** simulate the real editing flow:

```clojure
(deftest enter-respects-buffer-text
  (let [db (setup-with-empty-block "block-1")
        ;; Simulate: user typed but buffer not committed to DB yet
        session {:ui {:editing-block-id "block-1"}
                 :buffer {"block-1" "typed text"}}]
    ;; Handler should NOT auto-outdent because buffer has content
    ;; (even though DB text is empty)
    (is (= :split (determine-enter-behavior db session "block-1")))))
```

**Rule**: When testing editing behaviors, consider whether the handler reads from:
- **DB**: Pre-committed state (unit tests work fine)
- **DOM/Buffer**: Live editing state (needs session-aware test)

---

## Unit Tests (Kernel Layer)

Test pure functions with direct DB manipulation:

```clojure
(deftest transaction-applies-ops
  (let [db (db/empty-db)
        ops [{:op :create :id "a" :type :block :props {:text "hi"}}
             {:op :place :id "a" :under :doc :at :last}]
        result (tx/transact db ops)]
    (is (= "hi" (get-in result [:nodes "a" :props :text])))))
```

---

## Integration Tests (Session Layer)

Test flows that involve session state:

```clojure
(deftest selection-and-navigation
  (let [db (demo-db)
        session (empty-session)]
    ;; Dispatch selection intent
    (let [{:keys [session-updates]} (api/dispatch db session {:type :selection :ids "a"})]
      ;; Verify session updates
      (is (= #{"a"} (get-in session-updates [:selection :nodes]))))))
```

---

## Property-Based Testing

Use [test.check](https://clojure.org/guides/test_check_beginner) for invariants:

```clojure
(defspec all-nexus-actions-are-pure 50
  (prop/for-all [action (gen/elements [:editing/navigate-up :editing/navigate-down ...])]
    (let [state (sample-state)
          payload {:block-id "a"}
          result1 (nexus/handle-action state action payload)
          result2 (nexus/handle-action state action payload)]
      (= result1 result2))))
```

Good properties to test:
- **Purity**: Same input → same output
- **Round-trip**: `undo(redo(state)) == state`
- **Idempotency**: `apply(apply(state, op), op)` doesn't corrupt

Keep example-based tests alongside for documentation.

---

## State Machine Rules

Tests must respect the state machine:
- `:enter-edit` requires `:selection` state (select first)
- `:update-content` requires `:editing` state
- `:selection` works from any state

```clojure
;; Correct: transition through states
(-> session
    (dispatch {:type :selection :ids "a"})
    (dispatch {:type :enter-edit :block-id "a"}))
```

---

## Test Organization

```
test/
├── core/           # Kernel primitives (position, history)
├── plugins/        # Intent handlers (pure, isolated)
├── shell/          # Nexus actions, effects
├── integration/    # Multi-layer flows with session
├── view/           # Hiccup generation tests
├── macros/         # Multi-step scripts
└── fixtures.cljc   # Shared test data
```

---

## Troubleshooting

**Test passes but bug in browser**: Check if handler reads from DB vs DOM/buffer.

**Intent validation failed**: Check spec in `kernel.intent` registry.

**State machine blocked**: Use `(state-machine/allowed? state intent-type)` to debug.

---

## Future: E2E Tests

For contenteditable edge cases that can't be tested in pure CLJS, consider:

- [Playwright](https://playwright.dev/docs/input) with `locator.fill()` for contenteditable
- Use `pressSequentially()` when keyboard handling matters
- Assert focus with `toBeFocused()`

E2E should be **sparingly used** for critical user journeys only.
