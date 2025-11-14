# Replicant Quick Reference

## What is Replicant?

**Replicant is a data-driven rendering library** for ClojureScript that renders hiccup to the DOM efficiently. Key principles:
- UI is a pure function of application state
- No components, no local state, no subscriptions
- Just render the entire UI as hiccup data on every state change
- Replicant diffs and patches the DOM efficiently

**What Replicant is NOT:**
- Not React (different lifecycle API, no hooks/refs)
- Not a state management library
- Not a framework (just a renderer)

## Event Handlers: Two Approaches

> **Current Evo practice:** All production handlers are simple functions (see `components/block.cljs`). The data-driven pattern below is aspirational—use it only if you also wire the `set-dispatch!` plumbing.

### Function-Based (Simple, Direct)

```clojure
[:button {:on {:click (fn [e] (js/console.log "Clicked!"))}}
 "Click me"]
```

### Data-Driven (Testable, Serializable)

*Optional / not yet enabled in the shipped UI.*

### Editing Keys: Single Dispatcher Rule

- **While editing**, arrow keys (including Shift+Arrow) and Enter are handled entirely inside `components/block.cljs`. The component reads DOM cursor data and dispatches intents with the correct payload.
- The global keymap (`keymap/bindings_data.cljc`) must not bind those keys in the `:editing` context; double-dispatch causes cursor jumps and selection glitches.
- If you add a new editing shortcut, either wire it through the component or document why it is safe to keep it in the global handler (e.g., when it never needs DOM data).

Event handlers as **data vectors** instead of functions:

```clojure
;; Handler is data, not a function
[:input {:on {:input [[:update-text :event/target.value]]}}]
```

**Requires `set-dispatch!`** to process data handlers:

```clojure
(require '[replicant.dom :as r])

(r/set-dispatch!
 (fn [event-data handler-data]
   (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
     (let [dom-event (:replicant/dom-event event-data)]
       ;; handler-data is [[:update-text :event/target.value]]
       (handle-actions dom-event handler-data)))))
```

## Event Placeholders (Action Enrichment)

**Placeholders** inject runtime values into data-driven event handlers.

### Built-in Placeholders

Common placeholders you'll implement:

- `:event/target.value` - Extract input value
- `:event/target.checked` - Extract checkbox state
- `:event/key` - Extract pressed key
- `:event/prevent-default` - Call preventDefault()

### Implementing Placeholders

Create an `interpolate-actions` function to resolve placeholders:

```clojure
(defn interpolate-actions
  "Replace event placeholders with actual values from DOM event"
  [event actions]
  (clojure.walk/postwalk
   (fn [x]
     (case x
       :event/target.value (.. event -target -value)
       :event/target.checked (.. event -target -checked)
       :event/key (.-key event)
       :event/prevent-default (do (.preventDefault event) nil)
       x))
   actions))

(r/set-dispatch!
 (fn [event-data handler-data]
   (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
     (let [dom-event (:replicant/dom-event event-data)
           enriched-actions (interpolate-actions dom-event handler-data)]
       (handle-actions enriched-actions)))))
```

**How it works:**
```clojure
;; Before interpolation (data):
[[:update-text :event/target.value]]

;; After interpolation (with actual value):
[[:update-text "Hello world"]]
```

### Common Mistakes

```clojure
;; ❌ WRONG - dot instead of slash
:event.target/value

;; ✅ CORRECT - slash separates namespace
:event/target.value

;; ❌ WRONG - this is an internal Replicant thing
[:replicant/input-value]
```

## Lifecycle Hooks

Replicant provides lifecycle hooks but uses a **completely different API from React**.

### ⚠️ NOT React's `:ref`

```clojure
;; ❌ WRONG - This is React, not Replicant
[:span {:ref (fn [elem] (.focus elem))}]

;; ✅ CORRECT - Use :replicant/on-mount or :replicant/on-render
[:span {:replicant/on-mount (fn [{:replicant/keys [node]}]
                               (.focus node))}]
```

### Available Hooks

- **`:replicant/on-mount`** - Called ONLY on initial render
- **`:replicant/on-render`** - Called on EVERY render (mount, update, unmount)
- **`:replicant/on-update`** - Called only on updates (NOT mount or unmount)
- **`:replicant/on-unmount`** - Called ONLY when element is removed

### Hook Event Data Map

Lifecycle hooks receive a map with these keys:

```clojure
{:replicant/trigger :replicant.trigger/life-cycle
 :replicant/life-cycle <lifecycle-phase>  ; see below
 :replicant/node <DOM element>
 :replicant/remember <fn>  ; Store values for later retrieval
 :replicant/memory <any>}  ; Previously stored value
```

**`:replicant/life-cycle` values:**
- `:replicant.life-cycle/mount` - Initial render
- `:replicant.life-cycle/update` - Subsequent updates
- `:replicant.life-cycle/unmount` - Element removal

**Always destructure** with `:replicant/keys`:

```clojure
:replicant/on-mount (fn [{:replicant/keys [node]}]
  (.focus node)
  (.scrollIntoView node))
```

### Historical Bug: `mounting?`

Older versions of the block component destructured a `mounting?` flag that Replicant never provided. That code has been removed; the component now reads `:replicant/life-cycle` and uses `:replicant/on-mount` for mount-only work.

```clojure
;; ❌ PREVIOUSLY WRONG - mounting? doesn't exist in Replicant
:replicant/on-render (fn [{:replicant/keys [node mounting?]}]
  (when mounting? ...))

;; ✅ CORRECT - Use :replicant/life-cycle (as implemented in src/components/block.cljs)
:replicant/on-render (fn [{:replicant/keys [node life-cycle]}]
  (when (= life-cycle :replicant.life-cycle/mount)
    ...))

;; ✅ BETTER - Use :replicant/on-mount for mount-only logic
:replicant/on-mount (fn [{:replicant/keys [node]}]
  ...)
```

**Historical reference**: see `docs/VIEW_TESTING_SUMMARY.md` for the postmortem.

### Memory: Storing State Across Renders

Use `:replicant/remember` to store values, retrieve with `:replicant/memory`:

```clojure
[:div {:replicant/on-mount
       (fn [{:replicant/keys [node remember]}]
         ;; Create and store third-party lib instance
         (let [chart (.createChart js/someLib node)]
           (remember chart)))

       :replicant/on-update
       (fn [{:replicant/keys [memory]}]
         ;; Retrieve stored chart instance
         (.updateData memory new-data))

       :replicant/on-unmount
       (fn [{:replicant/keys [memory]}]
         ;; Clean up stored instance
         (.destroy memory))}]
```

Values are stored in a **WeakMap** associated with the DOM node.

### Required: Enable Lifecycle Hooks

You MUST call `set-dispatch!` to enable lifecycle hooks:

```clojure
(require '[replicant.dom :as r])

(r/set-dispatch!
 (fn [event-data handler-data]
   (cond
     ;; Handle lifecycle hooks (REQUIRED)
     (= :replicant.trigger/life-cycle (:replicant/trigger event-data))
     (when (fn? handler-data)
       (handler-data event-data))

     ;; Handle DOM events (optional - only if using data-driven handlers)
     (= :replicant.trigger/dom-event (:replicant/trigger event-data))
     (when (fn? handler-data)
       (handler-data (:replicant/dom-event event-data))))))
```

### Example: Auto-focus with Initial Content

```clojure
[:span {:contentEditable true
        :replicant/on-mount
        (fn [{:replicant/keys [node]}]
          ;; Set initial content ONLY on mount
          (set! (.-textContent node) "Hello")
          (.focus node))

        :replicant/on-render
        (fn [{:replicant/keys [node life-cycle]}]
          ;; Focus on every render, but don't reset content
          (when-not (= life-cycle :replicant.life-cycle/unmount)
            (.focus node)))}]
```

**See**: `src/components/block.cljs` for the shipped implementation (includes the `:replicant/life-cycle` guard described below).

---

## How We Use Replicant in Evo

### Architecture: Replicant as View Layer Only

```
User Interaction
      ↓
Component (Replicant)         <-- Pure render function
      ↓
Dispatch Intent              <-- Describe what happened
      ↓
Plugin (Intent Handler)      <-- Calculate operations
      ↓
Kernel (Transaction)         <-- Apply ops to DB
      ↓
Component Re-renders         <-- Replicant diffs & patches DOM
```

**Key Principle:** Components never mutate state directly. They dispatch intents.

### Pattern: Event Handlers Dispatch Intents

```clojure
;; components/block.cljs
(defn handle-arrow-up [e db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (when (:first-row? cursor-pos)
      (.preventDefault e)
      ;; Dispatch intent with context
      (on-intent {:type :navigate-with-cursor-memory
                  :direction :up
                  :current-block-id block-id
                  :current-text (.-textContent target)
                  :current-cursor-pos (.-anchorOffset (.getSelection js/window))}))))

;; Component definition
(defn Block [{:keys [db block-id on-intent] :as props}]
  [:div.block
   {:on {:keydown (fn [e] (handle-keydown e db block-id on-intent))}}
   ...])
```

**Why:** Component describes the event, plugin decides what ops to apply.

### Pattern: Lifecycle Hooks for DOM Interaction

We use `:replicant/on-render` for **ephemeral DOM state only**:
- Focus management
- Cursor positioning
- Scroll-into-view
- Updating mock-text for cursor detection

```clojure
;; Editing mode - focus and set cursor
[:span.content-edit
 {:contentEditable true
  :replicant/on-render (fn [{:replicant/keys [node]}]
                         ;; Focus element
                         (.focus node)

                         ;; Position cursor based on DB state
                         (when-let [cursor-pos (q/cursor-position db)]
                           (set-cursor-position! node cursor-pos))

                         ;; Update mock-text for boundary detection
                         (update-mock-text! node (.-textContent node)))}]
```

**Key Insight:** Cursor position is stored in DB (`:cursor-position`), but **applying** it requires DOM manipulation in lifecycle hook. To avoid reapplying the same hint on every render, store the last applied value on the DOM node (see `__lastAppliedCursorPos` in `components/block.cljs`).

### Pattern: Atoms for Component-Local State

Use atoms **sparingly** for state that:
1. Doesn't affect other components
2. Shouldn't be in history (undo/redo)
3. Is truly UI-only

```clojure
;; components/block.cljs
(defn Block [props]
  (let [;; Prevent blur event firing after Escape key
        exiting-edit? (atom false)

        ;; Track if we're in initial render
        initializing? (atom true)]
    [:div.block
     ;; Use atoms to coordinate event handlers
     {:on {:keydown (fn [e]
                      (when (= (.-key e) "Escape")
                        (reset! exiting-edit? true)))
           :blur (fn [e]
                   (when-not @exiting-edit?
                     (on-intent {:type :exit-edit})))}}]))
```

**Rule:** If state could affect another block or needs undo, put it in DB via intent. Otherwise, atom is OK.

### Pattern: Mock-text Technique for Cursor Detection

Replicant's `:replicant/on-render` enables Logseq's mock-text technique:

```clojure
;; Hidden div that mirrors contenteditable text
[:div#mock-text {:style {:position "absolute"
                         :visibility "hidden"
                         :white-space "pre-wrap"}}]

;; On every input, update mock-text to mirror the editing element's position/width
(defn update-mock-text! [elem text]
  (when-let [mock-elem (js/document.getElementById "mock-text")]
    (let [rect (.getBoundingClientRect elem)]
      (set! (.. mock-elem -style -top) (str (.-top rect) "px"))
      (set! (.. mock-elem -style -left) (str (.-left rect) "px"))
      (set! (.. mock-elem -style -width) (str (.-width rect) "px")))
    (set! (.-innerHTML mock-elem) "")
    (doseq [[idx c] (map-indexed vector (seq text))]
      (let [span (.createElement js/document "span")]
        (.setAttribute span "id" (str "mock-text_" idx))
        (set! (.-textContent span) (str c))
        (.appendChild mock-elem span)))))

;; Detect cursor row position
(defn detect-cursor-row-position [elem]
  (when-let [cursor-rect (get-caret-rect elem)]
    (let [tops (get-mock-text-tops)
          cursor-top (.-top cursor-rect)]
      {:first-row? (= (first tops) cursor-top)
       :last-row? (= (last tops) cursor-top)})))
```

**Why:** Contenteditable doesn't expose "what row am I on?" API. Mock-text gives us character positions via `getBoundingClientRect()`.

### What NOT to Do with Replicant

❌ **Don't use `:ref`** - Use lifecycle hooks instead
❌ **Don't store app state in atoms** - Use DB + intents
❌ **Don't call DOM APIs outside lifecycle hooks** - You'll lose in diffing
❌ **Don't mutate DOM directly** - Let Replicant handle it

✅ **Do use lifecycle hooks** for DOM-only operations
✅ **Do dispatch intents** for all state changes
✅ **Do keep components pure** - same props = same render
✅ **Do use atoms** for truly local, ephemeral UI state

---

## Testing Replicant Components

Replicant's data-driven design makes testing **much easier** than traditional React testing.

### Why Replicant Tests Are Better

**Traditional React**:
```javascript
// Need JSDOM, enzyme, or @testing-library
render(<MyComponent state={...} />)
screen.getByRole('button').click()  // Requires DOM simulation
```

**Replicant**:
```clojure
;; Just call the function!
(def hiccup (MyComponent {:state ...}))
;; Inspect hiccup (it's data!)
(is (= [:button ...] (find-element hiccup :button)))
;; Extract actions (also data!)
(def actions (select-actions hiccup :button [:on :click]))
```

### Three Testing Levels

#### 1. View Tests (Unit)

Test that components render correct hiccup:

```clojure
(deftest block-view-test
  (let [db {:nodes {"a" {:type :block :props {:text "Hello"}}}}
        hiccup (Block {:db db :block-id "a" :on-intent identity})]
    (is (= "Hello" (extract-text hiccup))
        "Block displays correct text")))
```

**Benefits**:
- No browser required
- Fast (milliseconds)
- Precise assertions
- Works in REPL

#### 2. Action Extraction Tests

Extract and verify data-driven event handlers:

```clojure
(deftest block-actions-test
  (let [hiccup (Block {:db db :block-id "a" :on-intent identity})
        input-actions (select-actions hiccup :.content-edit [:on :input])]
    (is (= [[:update-content "a" :event/target.value]]
           input-actions)
        "Input dispatches correct action")))
```

**Benefits**:
- Test event handlers without DOM
- Verify action data structure
- No mocking required

#### 3. Integration Tests

Test full render → action → update → re-render cycle:

```clojure
(deftest typing-integration-test
  (let [db (create-test-db)
        ;; 1. Render
        view-1 (Block {:db db :block-id "a" :on-intent identity})
        ;; 2. Extract action
        actions (select-actions view-1 :.content-edit [:on :input])
        ;; 3. Simulate typing (replace placeholder)
        concrete-actions (interpolate-placeholders
                          actions {:event/target.value "Hello"})
        ;; 4. Apply intent
        {:keys [ops]} (intent/apply-intent db concrete-actions)
        db-2 (:db (tx/interpret db ops))
        ;; 5. Re-render
        view-2 (Block {:db db-2 :block-id "a" :on-intent identity})]
    (is (= "Hello" (get-in db-2 [:nodes "a" :props :text])))
    (is (= "Hello" (extract-text view-2)))))
```

### REPL Component Testing

```clojure
;; In REPL - test instantly!
(require '[components.block :as block])

(def db {:nodes {"a" {:type :block :props {:text "Test"}}}})

(def view (block/Block {:db db :block-id "a" :on-intent prn}))

;; Inspect hiccup
(clojure.pprint/pprint view)

;; Extract actions
(select-actions view :.content-edit [:on :input])
;; => [[:update-content "a" :event/target.value]]
```

### E2E Testing with Playwright

For browser-specific behavior (cursor position, focus, keyboard):

```javascript
// test/e2e/navigation.spec.js
test('cursor position after navigation', async ({ page }) => {
  await page.goto('/blocks.html');
  await page.click('[data-block-id="a"]');
  await page.press('[contenteditable]', 'ArrowDown');

  const pos = await page.evaluate(() =>
    window.getSelection().anchorOffset
  );
  expect(pos).toBe(6);
});
```

**Use E2E for**:
- Cursor positioning
- Focus management
- getBoundingClientRect
- Keyboard navigation
- Accessibility

**Use unit tests for**:
- Component rendering
- Action dispatch
- State updates
- Business logic

### 📖 Complete Testing Guide

See **`docs/REPLICANT_TESTING.md`** for:
- Detailed testing patterns
- View test utilities
- Action extraction helpers
- Integration test examples
- Comparison with our current approach
- Gap analysis and recommendations

### Current State

**What we test well:**
- ✅ Kernel operations (transaction, ops, schema)
- ✅ Plugin intents (navigation, editing, selection)
- ✅ E2E browser behavior (Playwright)

**What's missing:**
- ❌ Component/view unit tests
- ❌ Action extraction from hiccup
- ❌ Fast integration tests (render → action → update)

**Recommendation**: Add view testing utilities and tests for critical components (Block, Sidebar). See `docs/REPLICANT_TESTING.md` for implementation guide.

---

## Troubleshooting & Common Pitfalls

### Lifecycle Hooks Not Firing

**Symptom:** `:replicant/on-mount` or `:replicant/on-render` do nothing.

**Cause:** Forgot to call `set-dispatch!`

**Fix:**
```clojure
(require '[replicant.dom :as r])

(r/set-dispatch!
 (fn [event-data handler-data]
   (when (= :replicant.trigger/life-cycle (:replicant/trigger event-data))
     (when (fn? handler-data)
       (handler-data event-data)))))
```

### `mounting?` (legacy helper)

If you ever see `{:replicant/keys [mounting?]}` in older code, replace it with:
```clojure
:replicant/on-render (fn [{:replicant/keys [node life-cycle]}]
  (when (= life-cycle :replicant.life-cycle/mount)
    ...))

:replicant/on-mount (fn [{:replicant/keys [node]}]
  ...)
```
That keeps the modern API while honoring the intent of the historical snippet.

### Event Handlers Don't Work

**Symptom:** Clicking buttons or typing in inputs does nothing.

**Possible causes:**

1. **Using data handlers without `set-dispatch!`**
   ```clojure
   ;; This won't work without set-dispatch!
   [:button {:on {:click [[:do-something]]}}]
   ```

   **Fix:** Either use function handlers OR set up dispatch:
   ```clojure
   ;; Option 1: Function handler
   [:button {:on {:click (fn [e] (do-something))}}]

   ;; Option 2: Set up dispatch for data handlers
   (r/set-dispatch! ...)
   ```

2. **Event bubbling prevented elsewhere**

   Check for `.stopPropagation()` or `.preventDefault()` calls in parent elements.

### Placeholders Not Being Replaced

**Symptom:** `:event/target.value` appears literally in your action data instead of the actual value.

**Cause:** `interpolate-actions` not called in dispatch handler.

**Fix:**
```clojure
(r/set-dispatch!
 (fn [event-data handler-data]
   (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
     (let [dom-event (:replicant/dom-event event-data)
           ;; MUST interpolate placeholders!
           enriched-actions (interpolate-actions dom-event handler-data)]
       (handle-actions enriched-actions)))))
```

### Component Re-renders Cause Infinite Loop

**Symptom:** Browser freezes, React devtools shows thousands of renders.

**Cause:** Dispatching intents from lifecycle hooks causes re-render, which triggers the hook again.

**Fix:** Don't dispatch intents from lifecycle hooks. Only perform DOM operations:

```clojure
;; ❌ BAD - causes infinite loop
:replicant/on-render (fn [{:replicant/keys [node]}]
  (on-intent {:type :update-something})) ;; Triggers re-render!

;; ✅ GOOD - only DOM operations
:replicant/on-render (fn [{:replicant/keys [node]}]
  (.focus node)
  (.scrollIntoView node))
```

For state updates triggered by mount, dispatch from event handlers or use `js/setTimeout`:

```clojure
:replicant/on-mount (fn [{:replicant/keys [node]}]
  ;; Defer state update to next tick
  (js/setTimeout #(on-intent {:type :init-component}) 0))
```

### contentEditable Loses Cursor Position

**Symptom:** Cursor jumps to start/end when typing in contenteditable elements.

**Cause:** Setting `.textContent` or `.innerHTML` on every render destroys cursor position.

**Fix:** Only set content on mount, preserve cursor manually:

```clojure
[:span {:contentEditable true
        :replicant/on-mount
        (fn [{:replicant/keys [node]}]
          ;; Set text ONLY on mount
          (set! (.-textContent node) initial-text))

        :replicant/on-render
        (fn [{:replicant/keys [node life-cycle]}]
          ;; Don't touch textContent on updates!
          ;; Only manage focus/cursor
          (when-not (= life-cycle :replicant.life-cycle/unmount)
            (.focus node)))}]
```

### DOM Mutations Don't Persist

**Symptom:** You change DOM attributes/styles in a lifecycle hook, but they disappear on next render.

**Cause:** Replicant diffs and resets attributes based on hiccup data.

**Fix:** Put the state in your hiccup data, not the DOM:

```clojure
;; ❌ BAD - Replicant will overwrite this
:replicant/on-mount (fn [{:replicant/keys [node]}]
  (.classList.add node "active"))

;; ✅ GOOD - Derive class from data
[:div {:class (when active? "active")}]
```

### Wrong Event Key: `:event.target/value` vs `:event/target.value`

**Symptom:** Placeholder not replaced, shows up as literal keyword in actions.

**Cause:** Wrong syntax - dot vs slash.

```clojure
;; ❌ WRONG
:event.target/value

;; ✅ CORRECT
:event/target.value
```

**Remember:** Slash separates namespace from name, just like `clojure.string/join`.

### Function vs Data Handler Confusion

**Symptom:** TypeError: handler is not a function.

**Cause:** Mixing function-based and data-based handlers without proper dispatch setup.

```clojure
;; Function handler - works immediately
[:button {:on {:click (fn [e] ...)}}]

;; Data handler - requires set-dispatch!
[:button {:on {:click [[:action]]}}]
```

Make sure `set-dispatch!` handles your handler type correctly.

---

## Quick Reference Card

### Lifecycle Hook Map Keys
```clojure
{:replicant/trigger :replicant.trigger/life-cycle
 :replicant/life-cycle #{:replicant.life-cycle/mount
                         :replicant.life-cycle/update
                         :replicant.life-cycle/unmount}
 :replicant/node <DOM-element>
 :replicant/remember <fn>    ; Store value
 :replicant/memory <any>}    ; Retrieve stored value
```

### Event Dispatch Map Keys
```clojure
{:replicant/trigger :replicant.trigger/dom-event
 :replicant/dom-event <JS-event>
 :replicant/node <DOM-element>}
```

### Common Placeholders
```clojure
:event/target.value     ; (.. event -target -value)
:event/target.checked   ; (.. event -target -checked)
:event/key              ; (.-key event)
:event/prevent-default  ; (.preventDefault event)
```

### Required Setup
```clojure
(require '[replicant.dom :as r])

;; MUST call this to enable hooks & data handlers
(r/set-dispatch!
 (fn [event-data handler-data]
   (cond
     (= :replicant.trigger/life-cycle (:replicant/trigger event-data))
     (when (fn? handler-data) (handler-data event-data))

     (= :replicant.trigger/dom-event (:replicant/trigger event-data))
     (let [enriched (interpolate-actions
                      (:replicant/dom-event event-data)
                      handler-data)]
       (handle-actions enriched)))))
```

---

## Reference

- **Official Docs**: https://replicant.fun/
- **Event Handlers**: https://replicant.fun/event-handlers/
- **Lifecycle Hooks**: https://replicant.fun/life-cycle-hooks/
- **API Docs**: https://cljdoc.org/d/no.cjohansen/replicant/
- **Source Code**: `~/Projects/best/replicant/src/replicant/core.cljc`
- **Example Usage**: `src/lab/app.cljs` (interpolate-actions), `src/components/block.cljs` (lifecycle hooks)
