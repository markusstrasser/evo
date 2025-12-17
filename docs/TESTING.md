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
       ╱E2E╲        ← Few: critical user journeys (81 tests)
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
        ops [{:op :create-node :id "a" :type :block :props {:text "hi"}}
             {:op :place :id "a" :under :doc :at :last}]
        {:keys [db]} (tx/interpret db ops)]
    (is (= "hi" (get-in db [:nodes "a" :props :text])))))
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
├── scripts/         # Multi-step scripts
└── fixtures.cljc   # Shared test data
```

---

## Troubleshooting

**Test passes but bug in browser**: Check if handler reads from DB vs DOM/buffer.

**Intent validation failed**: Check spec in `kernel.intent` registry.

**State machine blocked**: Use `(state-machine/allowed? state intent-type)` to debug.

---

## E2E Tests (Playwright)

Located in `test/e2e/`. Run with:

```bash
# Quick validation (recommended for development)
npm run test:e2e:smoke      # 15 critical tests, ~5 seconds

# Full suite
npm run test:e2e            # All tests (~5 minutes)
npm run test:e2e:headed     # With browser visible
npm run test:e2e:debug      # Playwright debugger
npm run test:e2e:ui         # Interactive UI mode

# CI
npx playwright test --project=smoke    # PR checks
npx playwright test --project=chromium # Nightly
```

### Test Tiers

**Smoke tests** (`{ tag: '@smoke' }`) cover critical paths (~15 tests, <30s):
- Basic typing and cursor behavior
- Block navigation
- Backspace merge (data loss prevention)
- Outdenting behavior

**Full suite** (~285 tests, ~5min) covers:
- All navigation patterns
- Clipboard operations
- Undo/redo
- Formatting, slash commands, etc.

### Tagging Tests

Use Playwright 1.42+ tag syntax:

```javascript
// Tag individual tests
test('critical feature', { tag: '@smoke' }, async ({ page }) => {});

// Tag describe blocks
test.describe('Critical Suite', { tag: '@smoke' }, () => {});
```

### Keyboard Helpers

Use cross-platform helpers from `test/e2e/helpers/keyboard.js`:

```javascript
import { 
  pressKeyOnContentEditable,  // Safe key press on contenteditable
  pressKeyCombo,              // Key + modifiers (Shift, Meta, etc.)
  pressHome, pressEnd,        // Cross-platform Home/End
  pressWordLeft, pressWordRight,  // Word navigation
  pressSelectToStart, pressSelectToEnd  // Shift+Home/End
} from './helpers/index.js';

// ✅ CORRECT: Use helpers for contenteditable
await pressKeyOnContentEditable(page, 'Enter');
await pressKeyCombo(page, 'ArrowDown', ['Shift']);
await pressHome(page);  // Cmd+Left on Mac, Home on Windows

// ❌ WRONG: Raw keyboard on contenteditable may not work
await page.keyboard.press('Home');  // Doesn't work on Mac!
```

### Why Cross-Platform Helpers?

macOS uses different keys than Windows/Linux:
- `Home`/`End` → `Cmd+Arrow` on Mac
- `Ctrl+Arrow` (word nav) → `Alt+Arrow` on Mac
- `Shift+Home` → `Cmd+Shift+Arrow` on Mac

### Test Helpers

```javascript
import { enterEditModeAndClick, selectPage } from './helpers/index.js';

// Enter edit mode on first block
await enterEditModeAndClick(page);

// Dispatch intents directly (bypass keyboard)
await page.evaluate(() => {
  window.TEST_HELPERS.dispatchIntent({ type: 'indent-selected' });
});
```

E2E tests focus on **user-facing behavior**, not internal state.
